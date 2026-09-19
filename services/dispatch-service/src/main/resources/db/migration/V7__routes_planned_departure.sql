-- =============================================================================
-- routes 에 계획 출발 시각 (DESIGN.md §5.3 · §5.4, Phase 5-1b)
--
-- 늦게 출발하는 것이 가장 흔한 지연 원인이고, 그것은 첫 ARRIVED 스캔 전에 이미 알 수 있다.
-- tracking 이 DEPARTED_CAMP 에서 편차 d = 실제 출발 − 계획 출발 을 뽑아 뒤따르는 stop 전부에
-- 전파하려면 계획 출발 시각이 route.assigned.v1 에 실려야 하고, §6.10 의 개정 발행은 계획
-- 결과가 아니라 저장된 라우트에서 페이로드를 만든다(취소된 stop 이 PlannedRoute 에 없다,
-- ADR-026 결정 4). 그 경로에는 DB 가 값의 출처여야 한다.
--
-- 값은 최적화기가 이미 갖고 있다 — RouteState 의 출발 앵커다(근무창 시작과 계획 시작 중
-- 늦은 쪽, §6.3 · ADR-030). 그동안 PlannedRoute 로 굳히면서 버려지고 있었다.
--
-- NULL 을 허용하는 이유는 V6 과 같다: V7 이전에 저장된 행에는 참인 값이 없고, 기본값을
-- 지어내면 "이 라우트는 이때 출발할 계획이었다" 는 거짓이 tracking 의 편차 계산에 그대로
-- 들어간다. NULL 은 "이 행은 V7 이전의 것" 이라는 참인 사실이고, 개정 발행은 required
-- 필드를 지어내는 대신 소리 내어 실패한다(RouteAssignedPayload).
-- =============================================================================

ALTER TABLE routes
  ADD COLUMN planned_departure TIMESTAMPTZ;

COMMENT ON COLUMN routes.planned_departure IS
  '캠프 출발 계획 시각. route.assigned.v1 의 summary.plannedDeparture 로 나가 tracking 의 '
  'DEPARTED_CAMP 편차 기준이 된다(§5.4). NULL 이면 V7 이전에 저장된 행이다 (Phase 5-1b).';
