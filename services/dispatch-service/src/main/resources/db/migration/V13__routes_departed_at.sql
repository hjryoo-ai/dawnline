-- ===========================================================================
-- routes.departed_at — 라우트가 캠프를 떠난 시각 (DESIGN.md §6.8 「편차」, ADR-072)
--
-- 재계획의 앵커는 마지막으로 닿은 stop 이다(ADR-048 결정 1). 닿은 stop 이 없으면 «모름»(no-anchor)이었고, at-risk 는
-- 설계상 출발 지연에서 첫 stop 전에 발화하므로(§5.4) 7-4 에서 29/32 · 24/28 이 그 자리였다(7-0 B1, 근거: 관측).
-- 출발을 아는 것은 tracking 이고 그 사실은 delivery.route-departed 로 온다 — dispatch 가 소비해 여기 둔다.
--
-- 처음 온 값만 남는다(COALESCE — route_stops.actual_at 과 같은 규칙). 개정 번호로 거르지 않는다 — 출발은 사실이다(ADR-047 결정 3).
-- NULL 은 「아직 떠나지 않았다(또는 모른다)」 — 기본값을 주면 모든 라우트가 떠난 것이 된다(V5 · V6 · V7 · V10 과 같은 이유).
-- 인덱스는 넣지 않는다 — PK 로만 닿는다(불변규칙 11).
-- ===========================================================================

ALTER TABLE routes ADD COLUMN departed_at TIMESTAMPTZ;

COMMENT ON COLUMN routes.departed_at IS
  '캠프를 떠난 시각 — delivery.route-departed 의 departedAt, 처음 온 값. 닿은 stop 이 없을 때 재계획의 앵커다: '
  '편차 = departed_at − planned_departure (ADR-072).';
