import { useEffect, useState } from 'react';
import { useApi } from '../api/ApiContext';
import type { Schemas } from '../api/client';
import { ProblemNotice } from '../components/ProblemNotice';
import { DASH, formatCount, formatKrw, formatRatio, formatTime, shortId } from '../format';
import { useLoad } from './useLoad';

/** 캠프의 이름 — 코드(`wave.closed` 의 스냅샷)가 없으면 id 를 줄여 쓴다(§5.3 「캠프 코드」). */
export function campName(camp: Pick<Schemas['CampSummary'], 'campId' | 'campCode'>): string {
  return camp.campCode ?? shortId(camp.campId);
}

/**
 * 캠프 대시보드 — 웨이브 · 계획 · 정시율 두 기준 · 개정 수 · 예외 목록(ADR-057 결정 3).
 */
export function Dashboard() {
  const api = useApi();
  const camps = useLoad(() => api.camps(), [api]);
  const [campId, setCampId] = useState<string | null>(null);

  const list = camps.data?.camps ?? [];
  useEffect(() => {
    if (campId === null && list[0]) {
      setCampId(list[0].campId);
    }
  }, [campId, list]);

  return (
    <section>
      <h2>캠프 대시보드</h2>
      <ProblemNotice failure={camps.failure} />
      {camps.data && list.length === 0 && <p>읽기 모델에 웨이브가 있는 캠프가 아직 없습니다.</p>}
      {list.length > 0 && (
        <label className="field inline">
          캠프
          <select value={campId ?? ''} onChange={(event) => setCampId(event.target.value)}>
            {list.map((camp) => (
              <option key={camp.campId} value={camp.campId}>
                {campName(camp)} · 웨이브 {camp.waves}
              </option>
            ))}
          </select>
        </label>
      )}
      {campId && <CampPanel campId={campId} />}
    </section>
  );
}

function CampPanel({ campId }: { campId: string }) {
  const api = useApi();
  const waves = useLoad(() => api.waves(campId), [api, campId]);
  const kpi = useLoad(() => api.deliveryKpi(), [api]);
  const exceptions = useLoad(() => api.exceptions(campId), [api, campId]);
  const campKpi = kpi.data?.camps.find((camp) => camp.campId === campId) ?? null;

  return (
    <>
      <h3>정시율 — 최근 24시간(UTC 정시 버킷 24개)</h3>
      <ProblemNotice failure={kpi.failure} />
      {kpi.data && <KpiPanel kpi={campKpi} window={kpi.data} />}

      <h3>웨이브 — 지금 ± 24시간</h3>
      <ProblemNotice failure={waves.failure} />
      {waves.data && <WaveTable waves={waves.data.waves} />}

      <h3>예외 — 취소됐는데 배송됨</h3>
      <ProblemNotice failure={exceptions.failure} />
      {exceptions.data && <ExceptionPanel list={exceptions.data} />}
    </>
  );
}

function KpiPanel({ kpi, window }: { kpi: Schemas['CampKpi'] | null; window: Schemas['DeliveryKpi'] }) {
  if (!kpi) {
    return <p>이 캠프의 결과가 창 안에 없습니다.</p>;
  }
  return (
    <dl className="kpi" aria-label="정시율">
      <div>
        <dt>원 약속 기준</dt>
        <dd title="null 이면 창 안에 결과가 없다 — 0% 가 아니다">{formatRatio(kpi.onTimeRatioPromised)}</dd>
      </div>
      <div>
        <dt>개정 약속 기준</dt>
        <dd>{formatRatio(kpi.onTimeRatioRevised)}</dd>
      </div>
      <div>
        <dt>약속 개정</dt>
        <dd>{formatCount(kpi.revised)}건</dd>
      </div>
      <div>
        <dt>완료 · 실패</dt>
        <dd>
          {formatCount(kpi.delivered)} · {formatCount(kpi.failed)}
        </dd>
      </div>
      <p className="muted">
        {formatTime(window.firstBucket)} ~ {formatTime(window.lastBucket)} · 약속을 몰라 모집단에서 빠진 결과(전 캠프){' '}
        {formatCount(window.outcomeWithoutPromise)}건
      </p>
    </dl>
  );
}

function WaveTable({ waves }: { waves: Schemas['WaveSummary'][] }) {
  if (waves.length === 0) {
    return <p>이 창에 웨이브가 없습니다.</p>;
  }
  return (
    <table aria-label="웨이브">
      <thead>
        <tr>
          <th>컷오프</th>
          <th>티어</th>
          <th>상태</th>
          <th>주문</th>
          <th>라우트</th>
          <th>미배정</th>
          <th>비용</th>
          <th>계획 시간</th>
          <th />
        </tr>
      </thead>
      <tbody>
        {waves.map((wave) => (
          <tr key={wave.waveId}>
            <td>{formatTime(wave.cutoffAt)}</td>
            <td>{wave.serviceTier ?? DASH}</td>
            <td>
              <span className={`status status-${(wave.status ?? 'unknown').toLowerCase()}`}>{wave.status ?? DASH}</span>
            </td>
            <td>{formatCount(wave.orderCount)}</td>
            <td>{formatCount(wave.routeCount)}</td>
            <td>{formatCount(wave.unassignedCount)}</td>
            <td>{formatKrw(wave.totalCostKrw)}</td>
            <td>{wave.planDurationMs === null || wave.planDurationMs === undefined ? DASH : `${(wave.planDurationMs / 1000).toFixed(1)}초`}</td>
            <td>
              <a href={`#/waves/${encodeURIComponent(wave.waveId)}`}>지도</a>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function ExceptionPanel({ list }: { list: Schemas['ExceptionList'] }) {
  return (
    <div aria-label="예외 목록">
      <p>
        전체 <strong>{formatCount(list.total)}건</strong>
        {list.total > list.orders.length && ` — 최근 ${list.orders.length}건만 보인다`}
      </p>
      <p className="muted">
        해소 여부(환불·회수)는 이 시스템이 모릅니다. 목록에서 빠지지 않으며, 목록에 있다는 것은 「처리되지 않았다」가 아니라
        「이런 일이 있었다」입니다.
      </p>
      {list.orders.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>주문</th>
              <th>배송 완료</th>
              <th>웨이브</th>
              <th>라우트</th>
            </tr>
          </thead>
          <tbody>
            {list.orders.map((order) => (
              <tr key={order.orderId}>
                <td>
                  <code>{shortId(order.orderId)}</code>
                </td>
                <td>{formatTime(order.deliveredAt)}</td>
                <td>{shortId(order.waveId)}</td>
                <td>{shortId(order.routeId)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
