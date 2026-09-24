import { useState } from 'react';
import { useApi } from '../api/ApiContext';
import type { Schemas } from '../api/client';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { ProblemNotice, type Failure } from '../components/ProblemNotice';
import { DASH, formatCount, formatKrw, formatTime, shortId } from '../format';
import { RouteCanvas, STOP_COLORS } from '../map/RouteCanvas';
import { session } from '../session';
import { useLoad } from './useLoad';

type Command =
  | { kind: 'close' }
  | { kind: 'reassign'; routeId: string; orderId: string; targetRouteId: string };

/**
 * 라우트 지도 — stop 순서 폴리라인 · 상태 색 · at-risk 강조 · 재배정 · 조기 마감(ADR-057 결정 3).
 * 커맨드의 결과는 감사 id 와 함께 보인다 — `UNKNOWN` 을 사람이 해소할 때 그 id 로 코어의 로그를 찾는다(ADR-052 결정 3).
 */
export function RouteMap({ waveId }: { waveId: string }) {
  const api = useApi();
  const wave = useLoad(() => api.waveRoutes(waveId), [api, waveId]);
  const [selected, setSelected] = useState<string | null>(null);
  const [command, setCommand] = useState<Command | null>(null);
  const [pending, setPending] = useState(false);
  const [commandFailure, setCommandFailure] = useState<Failure | null>(null);
  const [done, setDone] = useState<string | null>(null);

  const routes = wave.data?.routes ?? [];
  const routeId = selected ?? routes[0]?.routeId ?? null;
  const summary = routes.find((route) => route.routeId === routeId) ?? null;

  async function send(reason: string | null) {
    if (!command) {
      return;
    }
    setPending(true);
    setCommandFailure(null);
    const result =
      command.kind === 'close'
        ? await api.closeWave(waveId, { reason: reason ?? '' })
        : await api.reassign(command.routeId, command.orderId, { targetRouteId: command.targetRouteId });
    setPending(false);
    setCommand(null);
    if (result.ok) {
      setDone(`적용됐습니다 · 감사 id ${result.auditId ?? DASH}`);
      wave.reload();
    } else {
      setDone(null);
      setCommandFailure({ status: result.status, problem: result.problem });
    }
  }

  return (
    <section>
      <h2>
        라우트 지도 <small>웨이브 {shortId(waveId)}</small>
      </h2>
      <p>
        <a href="#/">← 대시보드</a>
      </p>
      <ProblemNotice failure={wave.failure} />
      <ProblemNotice failure={commandFailure} />
      {done && (
        <p role="status" className="notice notice-ok">
          {done}
        </p>
      )}

      {wave.data && (
        <>
          <div className="actions">
            <button
              type="button"
              className="danger"
              onClick={() => setCommand({ kind: 'close' })}
              disabled={wave.data.planId !== null}
              title={wave.data.planId !== null ? '계획이 있는 웨이브는 이미 닫혔다' : undefined}
            >
              웨이브 조기 마감
            </button>
          </div>
          {routes.length === 0 ? (
            <p>아직 계획이 없습니다 — 라우트가 없습니다.</p>
          ) : (
            <div className="map-layout">
              <RouteList routes={routes} selected={routeId} onSelect={setSelected} />
              {routeId && summary && (
                <RouteDetailPanel
                  key={`${routeId}:${summary.revision ?? ''}`}
                  routeId={routeId}
                  atRisk={summary.atRisk === true}
                  depot={wave.data.depot ?? null}
                  otherRoutes={routes.filter((route) => route.routeId !== routeId)}
                  onReassign={(orderId, targetRouteId) =>
                    setCommand({ kind: 'reassign', routeId, orderId, targetRouteId })
                  }
                />
              )}
            </div>
          )}
        </>
      )}

      {command?.kind === 'close' && (
        <ConfirmDialog
          title="웨이브 조기 마감"
          confirmLabel="마감"
          reason={{ label: '이유', maxLength: 200 }}
          pending={pending}
          onConfirm={send}
          onCancel={() => setCommand(null)}
        >
          컷오프 전에 이 웨이브를 닫습니다. 그 뒤에 접수된 주문은 다음 웨이브로 가고 약속이 개정됩니다.
        </ConfirmDialog>
      )}
      {command?.kind === 'reassign' && (
        <ConfirmDialog
          title="stop 재배정"
          confirmLabel="재배정"
          pending={pending}
          onConfirm={send}
          onCancel={() => setCommand(null)}
        >
          주문 {shortId(command.orderId)} 를 라우트 {shortId(command.routeId)} 에서 {shortId(command.targetRouteId)} 로
          옮깁니다. 두 라우트의 revision 이 오릅니다.
        </ConfirmDialog>
      )}
    </section>
  );
}

function RouteList(props: {
  routes: Schemas['RouteSummary'][];
  selected: string | null;
  onSelect: (routeId: string) => void;
}) {
  return (
    <table aria-label="라우트">
      <thead>
        <tr>
          <th>라우트</th>
          <th>상태</th>
          <th>진행</th>
          <th>비용</th>
          <th />
        </tr>
      </thead>
      <tbody>
        {props.routes.map((route) => (
          <tr
            key={route.routeId}
            className={`${route.routeId === props.selected ? 'selected' : ''} ${route.atRisk ? 'at-risk' : ''}`}
          >
            <td>
              <button type="button" className="link" onClick={() => props.onSelect(route.routeId)}>
                {shortId(route.routeId)}
              </button>
              {route.atRisk && <span className="badge-risk">at-risk</span>}
            </td>
            <td>
              {route.status ?? DASH} <small>r{route.revision ?? DASH}</small>
            </td>
            <td>
              {formatCount(route.completedCount)}/{formatCount(route.stopCount)}
              {route.failedCount ? ` · 실패 ${route.failedCount}` : ''}
            </td>
            <td>{formatKrw(route.costKrw)}</td>
            <td>{route.departedAt ? `출발 ${formatTime(route.departedAt)}` : `계획 ${formatTime(route.plannedDeparture)}`}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function RouteDetailPanel(props: {
  routeId: string;
  atRisk: boolean;
  depot: Schemas['Depot'] | null;
  otherRoutes: Schemas['RouteSummary'][];
  onReassign: (orderId: string, targetRouteId: string) => void;
}) {
  const api = useApi();
  const detail = useLoad(() => api.route(props.routeId), [api, props.routeId]);
  const [target, setTarget] = useState(props.otherRoutes[0]?.routeId ?? '');
  const stops = detail.data ? [...detail.data.stops].sort((a, b) => a.seq - b.seq) : [];

  return (
    <div className="route-detail">
      <ProblemNotice failure={detail.failure} />
      {detail.data && (
        <>
          <RouteCanvas depot={props.depot} stops={detail.data.stops} atRisk={props.atRisk} tiles={session.tiles()} />
          <label className="field inline">
            재배정 대상 라우트
            <select value={target} onChange={(event) => setTarget(event.target.value)}>
              {props.otherRoutes.map((route) => (
                <option key={route.routeId} value={route.routeId}>
                  {shortId(route.routeId)}
                </option>
              ))}
            </select>
          </label>
          <table aria-label="stop 순서">
            <thead>
              <tr>
                <th>#</th>
                <th>상태</th>
                <th>도착 계획</th>
                <th>주문</th>
              </tr>
            </thead>
            <tbody>
              {stops.map((stop) => (
                <tr key={stop.seq}>
                  <td>{stop.seq}</td>
                  <td>
                    <span className="dot" style={{ background: STOP_COLORS[stop.status] ?? '#6b7280' }} />
                    {stop.status}
                  </td>
                  <td>{formatTime(stop.plannedArrival)}</td>
                  <td>
                    {stop.orderIds.map((orderId) => (
                      <span key={orderId} className="order">
                        <code>{shortId(orderId)}</code>
                        <button
                          type="button"
                          disabled={!target || stop.status !== 'PLANNED'}
                          onClick={() => props.onReassign(orderId, target)}
                        >
                          재배정
                        </button>
                      </span>
                    ))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}
