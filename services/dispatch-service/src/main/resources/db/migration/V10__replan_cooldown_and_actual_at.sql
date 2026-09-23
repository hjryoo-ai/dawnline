-- ===========================================================================
-- 재계획이 자기 DB 로 풀 수 있게 하는 두 칸 (DESIGN.md §5.3 · §6.8, Phase 5-3, ADR-048)
--
-- 1) routes.last_replanned_at — §6.8 5단계의 라우트당 10분 쿨다운.
--
--    ADR-046 결정 3 이 이 쿨다운을 dispatch 에 맡겼다. tracking 의 Redis 쿨다운은 **알림 수**를
--    지키지 정확성을 지키지 않는다: Redis 가 죽으면 중복 at-risk 가 나가고(§7.2 가 허용으로
--    정한 폴백), 두 at-risk 는 eventId 가 달라 processed_events 가 막지 못한다. 재계획이 두 번
--    도는 것을 막는 것은 이 컬럼뿐이고, 비교와 갱신은 재계획 트랜잭션 **안에서** 한 문장으로
--    한다(UPDATE ... WHERE last_replanned_at IS NULL OR last_replanned_at <= now − 쿨다운).
--    읽고 나서 쓰면 두 소비자가 같은 값을 읽는 창이 생긴다.
--
--    NULL 은 "아직 재계획한 적이 없다" 는 참인 사실이다. 기본값을 주면 과거의 라우트가 전부
--    "그때 재계획했다" 고 말하게 된다 — V5·V6·V7 이 같은 이유로 NULL 을 택했다.
--
-- 2) route_stops.actual_at — 그 stop 에 **처음 닿은** 시각.
--
--    5-5 의 전이(delivery.status → route_stops.status)가 occurredAt 을 함께 적는다. 계약은
--    그 값을 이미 싣고 있었고 버리고 있었을 뿐이다. 이 칸이 없으면 §6.8 의 편차는 dispatch
--    안에 존재하지 않고, 그것을 at-risk 페이로드에서 읽는 순간 「진실 하나」가 소속은
--    dispatch · 시각은 tracking 으로 갈린다(ADR-048 결정 1).
--
--    편차 = 마지막으로 닿은 stop 의 (actual_at − planned_arrival).
--
--    **덮어쓰지 않는다.** ARRIVED | COMPLETED | FAILED 중 먼저 온 것이 쓴다. 덮으면 이 값은
--    도착이 아니라 완료가 되고, 편차가 「얼마나 늦게 도착했나」에서 「거기서 머문 시간까지 더한
--    값」으로 조용히 바뀐다 — 그리고 그 변화는 **값을 보아서는 알 수 없다**. 순서가 뒤바뀌어
--    COMPLETED 가 먼저 오면 체류 시간만큼 늦은 값이 남는데, 보정하지 않는 이유는 보정이
--    *추정*이기 때문이다. 이 컬럼의 값은 전부 관측이어야 다음 판단이 선다.
--
--    NULL 은 "아직 닿지 않았다" = §6.8 이 다시 푸는 대상이다. 편차를 **모르는 것**과 0 은
--    다르다(ADR-048 결정 1, 기각 (8)).
--
-- 인덱스를 추가하지 않는다 (불변규칙 11). 두 질의 모두 route_id 로 좁힌 뒤 라우트 하나 안을
-- 본다: 쿨다운은 routes PK 한 건이고, "마지막으로 닿은 stop" 은 route_stops 의
-- UNIQUE (route_id, seq) 를 역순으로 한 건 읽는다. 라우트당 stop 은 설계 상한 120,
-- peak 데이터셋 실측 최대 90 이다(§6.9) — 이 규모에서 부분 인덱스를 더하면 쓰기마다 그것을
-- 갱신하는 비용만 남는다. 측정은 PR 설명에 EXPLAIN (ANALYZE 뒤) 으로 붙인다.
-- ===========================================================================

ALTER TABLE routes
  ADD COLUMN last_replanned_at TIMESTAMPTZ;

COMMENT ON COLUMN routes.last_replanned_at IS
  '§6.8 부분 재계획의 라우트당 쿨다운(10분) 기준 시각. 재계획 트랜잭션 안에서 비교·갱신한다 '
  '(ADR-046 결정 3, ADR-048 결정 7). NULL 이면 아직 재계획한 적이 없다.';

ALTER TABLE route_stops
  ADD COLUMN actual_at TIMESTAMPTZ;

COMMENT ON COLUMN route_stops.actual_at IS
  '그 stop 에 처음 닿은 시각 (delivery.status 의 occurredAt, ADR-048 결정 1). '
  'ARRIVED|COMPLETED|FAILED 중 먼저 온 것이 쓰고 덮어쓰지 않는다 — 덮으면 도착이 아니라 완료를 '
  '재게 된다. §6.8 의 편차 = 마지막으로 닿은 stop 의 (actual_at − planned_arrival). '
  'NULL 은 아직 닿지 않았다는 뜻이고, 편차를 모르는 것은 편차가 0 인 것과 다르다.';
