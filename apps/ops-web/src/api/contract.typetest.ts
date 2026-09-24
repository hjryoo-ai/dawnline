/**
 * ADR-056 기준 4·5·6 — 타입 수준 픽스처. `tsc` 가 이 파일을 검사한다: `@ts-expect-error` 가 붙은 줄이
 * 오류가 아니면 `tsc` 가 그 지시문을 오류로 말한다. 그래서 「막혀야 하는 것이 막힌다」와 「통과해야 하는 것이
 * 통과한다」를 한 파일이 함께 말한다.
 */
import { createApi, type Problem, type Schemas } from './client';

// --- 기준 4: 계약의 required 가 타입에 도착한다 · @Nullable 은 null 을 받는다 ---------------------------

export const kpi: Schemas['CampKpi'] = {
  campId: 'c', delivered: 1, failed: 0, onTimePromised: 1, onTimeRevised: 1, revised: 0,
  onTimeRatioPromised: null,
};
// @ts-expect-error campId 는 required 다
export const kpiWithoutCamp: Schemas['CampKpi'] = { delivered: 1, failed: 0, onTimePromised: 1, onTimeRevised: 1, revised: 0 };
// @ts-expect-error delivered 는 @Nullable 이 아니다 — null 이 아니다
export const kpiNullDelivered: Schemas['CampKpi'] = { campId: 'c', delivered: null, failed: 0, onTimePromised: 1, onTimeRevised: 1, revised: 0 };

export const unplanned: Schemas['WaveRoutes'] = { waveId: 'w', planId: null, depot: null, routes: [] };
// @ts-expect-error routes 는 required 다
export const withoutRoutes: Schemas['WaveRoutes'] = { waveId: 'w' };

export const stop: Schemas['RouteStop'] = {
  seq: 1, lat: 37.5, lng: 127.0, plannedArrival: '2026-09-24T00:00:00Z', status: 'PLANNED', orderIds: [],
};
// @ts-expect-error lat 은 required 다
export const stopWithoutLat: Schemas['RouteStop'] = { seq: 1, lng: 127.0, plannedArrival: 'x', status: 'PLANNED', orderIds: [] };

// --- 기준 5: 계약에 없는 호출은 컴파일 오류 · 결정 2 「화면의 입력은 계약에 있는 칸만」 --------------------

const api = createApi({ token: () => null });

export async function contractCalls() {
  await api.reassign('r', 'o', { targetRouteId: 't' });
  // @ts-expect-error 재배정 본문에 이유 칸은 없다 — 버려지는 입력
  await api.reassign('r', 'o', { targetRouteId: 't', reason: '지연' });
  await api.closeWave('w', { reason: '피크 대비 선마감' });
  // @ts-expect-error 조기 마감의 reason 은 required 다
  await api.closeWave('w', {});
  // @ts-expect-error 조기 마감 본문에 계약 밖 칸
  await api.closeWave('w', { reason: 'r', extra: 1 });
  // @ts-expect-error 없는 경로
  await api.call('/api/v1/nowhere', 'get', {});
  // @ts-expect-error 그 경로에 없는 메서드
  await api.call('/api/v1/camps', 'post', {});
}

// --- 기준 6: 오류 본문이 ProblemDetail 이고 code 칸이 있다 ---------------------------------------------

export async function errorBodies(): Promise<string | undefined> {
  const result = await api.camps();
  if (!result.ok) {
    const problem: Problem | null = result.problem;
    return problem?.code;
  }
  const campIds: string[] = result.data.camps.map((camp) => camp.campId);
  return campIds[0];
}
