-- ===========================================================================
-- 배송의 사실은 주문의 행에 (DESIGN.md §4 「dispatch 가 delivery.status 를 소비한다」 · §5.3, ADR-071)
--
-- route_stops.status 는 그 stop 의 어느 주문의 delivery.status 가 와도 stop 전체를 덮었다. 한 stop 에
-- 주문이 여럿이면(계획이 같은 지점을 합쳤거나, 재배정이 같은 지점의 stop 에 붙였거나) 하나가 나머지의
-- 사실을 대신 말한다 — 배송되지 않은 주문이 계획에서 끝난 것이 된다(DeliveryFactPerOrderIT, 근거:
-- 관측(재현됨)). 사실은 주문의 것이다(ADR-047). 그래서 주문의 행에 적는다:
--
--   status    PLANNED | ARRIVED | COMPLETED | FAILED. 취소는 적지 않는다 — 취소의 출처는
--             dispatch_candidates.status 하나다(§6.10). CHECK 제약은 두지 않는다 — route_stops.status 와
--             같은 이유다(§4.7 은 같은 major 안에서 enum 값 추가를 허용한다).
--   actual_at 그 주문에 처음 닿은 시각. 덮어쓰지 않는다(ADR-048 결정 1 의 규칙을 주문으로).
--
-- route_stops.status · actual_at 은 이제 이 행들에서 쓰기 때 다시 센 값이다(ADR-071 결정 2 — ADR-061 의 모양).
--
-- 기본값 PLANNED 는 뜻이 있는 값이다 — 계획이 만든 연결은 아직 아무도 닿지 않았다. 계획의 저장
-- (JdbcPlannedRouteRepository)이 이 칸을 모른 채 행을 넣어도 참이다.
--
-- 백필: 닿은 stop(ARRIVED · COMPLETED · FAILED)의 값을 그 주문들에 복사한다. 주문별 사실은 이전에 저장된 적이
-- 없어서 stop 의 값이 유일한 출처다 — 정정 전의 덮음이 그대로 옮겨 오지만, 그것을 가를 정보가 어디에도 없다.
-- 닿지 않은 stop(PLANNED · CANCELLED)의 주문은 기본값 PLANNED 다.
--
-- 인덱스는 넣지 않는다: 이 칸을 조건으로 찾는 질의가 없다 — 주문은 ix_rso_order(order_id)로, stop 의 주문들은
-- PK(stop_id, order_id)로 닿는다(불변규칙 11).
-- ===========================================================================

ALTER TABLE route_stop_orders
  ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PLANNED',
  ADD COLUMN actual_at TIMESTAMPTZ;

UPDATE route_stop_orders o
   SET status = s.status,
       actual_at = s.actual_at
  FROM route_stops s
 WHERE o.stop_id = s.id
   AND s.status IN ('ARRIVED', 'COMPLETED', 'FAILED');

COMMENT ON COLUMN route_stop_orders.status IS
  '이 주문의 배송 사실 — PLANNED | ARRIVED | COMPLETED | FAILED (ADR-071). 취소는 dispatch_candidates.status 가 말한다. '
  'route_stops.status 는 이 값들에서 쓰기 때 다시 센다.';
COMMENT ON COLUMN route_stop_orders.actual_at IS
  '이 주문에 처음 닿은 시각 — delivery.status 의 occurredAt, 덮어쓰지 않는다(ADR-048 결정 1 · ADR-071).';
