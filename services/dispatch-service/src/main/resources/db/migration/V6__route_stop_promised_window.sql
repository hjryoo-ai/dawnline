-- =============================================================================
-- route_stops 에 약속창 (DESIGN.md §5.3, Phase 5-1a)
--
-- route.assigned.v1 의 stop 은 promisedWindow 를 required 로 싣는다. 최초 발행은 계획
-- 결과에서 값을 얻지만(PlannedStop → Stop.promised()), §6.10 의 개정 발행은 저장된
-- 라우트에서 페이로드를 만든다 — 취소된 stop 이 PlannedRoute 에 없기 때문이다(ADR-026
-- 결정 4). 그 경로에는 DB 가 값의 출처여야 한다.
--
-- NULL 을 허용하는 이유: V6 이전에 저장된 행에는 참인 값이 없다. 기본값을 지어내면
-- "약속창이 이랬다" 는 거짓을 쓰는 것이고, 그 거짓이 tracking 의 at-risk 판정에 그대로
-- 들어간다. NULL 은 "이 행은 V6 이전의 것" 이라는 참인 사실이고, 개정 발행은 그때
-- required 필드를 지어내는 대신 **소리 내어 실패한다**(RouteAssignedPayload).
--
-- 새로 저장되는 행은 언제나 값을 갖는다 — 쓰는 경로가 하나뿐이고(JdbcPlannedRouteRepository)
-- 그쪽은 계획 결과에서 바로 채운다.
-- =============================================================================

ALTER TABLE route_stops
  ADD COLUMN promised_start TIMESTAMPTZ,
  ADD COLUMN promised_end   TIMESTAMPTZ;

COMMENT ON COLUMN route_stops.promised_start IS
  '이 stop 의 약속창 시작. NULL 이면 V6 이전에 저장된 행이다 (Phase 5-1a).';
COMMENT ON COLUMN route_stops.promised_end IS
  '이 stop 의 약속창 끝. tracking 의 at-risk 기준이다 (eta > promised_end - 15분, §5.4).';
