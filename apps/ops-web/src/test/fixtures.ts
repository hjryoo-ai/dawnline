import type { Schemas } from '../api/client';

export const CAMP = '0199c000-0000-7000-8000-00000000ca01';
export const CAMP_WITHOUT_CODE = '0199c000-0000-7000-8000-00000000ca02';
export const WAVE = '0199c000-0000-7000-8000-0000000000a1';
export const ROUTE = '0199c000-0000-7000-8000-0000000000b1';
export const OTHER_ROUTE = '0199c000-0000-7000-8000-0000000000b2';
export const ORDER = '0199c000-0000-7000-8000-0000000000c1';

export const camps: Schemas['CampList'] = {
  camps: [
    { campId: CAMP, campCode: 'CAMP-SEO-N', waves: 2, latestCutoffAt: '2026-09-24T15:00:00Z' },
    { campId: CAMP_WITHOUT_CODE, campCode: null, waves: 1, latestCutoffAt: null },
  ],
};

export const waves: Schemas['WaveList'] = {
  campId: CAMP,
  from: '2026-09-23T15:00:00Z',
  to: '2026-09-25T15:00:00Z',
  waves: [
    {
      waveId: WAVE, serviceTier: 'DAWN', cutoffAt: '2026-09-24T15:00:00Z', status: 'PLANNED', orderCount: 120,
      planId: 'plan-1', planDurationMs: 1234, totalCostKrw: 1250000, unassignedCount: 0, routeCount: 2,
    },
  ],
};

export const kpi: Schemas['DeliveryKpi'] = {
  firstBucket: '2026-09-23T16:00:00Z',
  lastBucket: '2026-09-24T15:00:00Z',
  outcomeWithoutPromise: 1,
  camps: [
    {
      campId: CAMP, delivered: 48, failed: 2, onTimePromised: 48, onTimeRevised: 0, revised: 3,
      onTimeRatioPromised: 0.96, onTimeRatioRevised: null,
    },
  ],
};

/** 계획이 아직 없는 웨이브 — 조기 마감을 누를 수 있다. */
export const openWave: Schemas['WaveRoutes'] = { waveId: WAVE, planId: null, depot: null, routes: [] };

export const routes: Schemas['WaveRoutes'] = {
  waveId: WAVE,
  planId: 'plan-1',
  depot: { lat: 37.64, lng: 127.03 },
  routes: [
    { routeId: ROUTE, vehicleId: 'v1', revision: 1, status: 'ASSIGNED', stopCount: 2, completedCount: 0, failedCount: 0, atRisk: true, costKrw: 600000 },
    { routeId: OTHER_ROUTE, vehicleId: 'v2', revision: 1, status: 'ASSIGNED', stopCount: 1, completedCount: 0, failedCount: 0, atRisk: null, costKrw: 650000 },
  ],
};

export const routeDetail: Schemas['RouteDetail'] = {
  routeId: ROUTE, planId: 'plan-1', vehicleId: 'v1', status: 'ASSIGNED', revision: 1, distanceM: 8000, durationS: 2400, costKrw: 600000,
  stops: [
    { seq: 1, lat: 37.65, lng: 127.04, plannedArrival: '2026-09-24T16:10:00Z', status: 'PLANNED', orderIds: [ORDER] },
    { seq: 2, lat: 37.66, lng: 127.05, plannedArrival: '2026-09-24T16:30:00Z', status: 'COMPLETED', orderIds: ['0199c000-0000-7000-8000-0000000000c2'] },
  ],
};
