-- =============================================================================
-- 편차는 라우트 행에 한 번 (DESIGN.md §5.4 ETA 재계산, ADR-070 결정 2)
--
-- 스캔 하나가 뒤따르는 배송마다 eta_at 을 다시 적었다 — 라우트당 O(n²) 의 쓰기다. 30-stop 라우트 하나를
-- 출발 · 도착 · 완료로 끝까지 돌리면 shipments 의 행 갱신이 990 이었다(배송당 33, 상태 전이만이면 90 —
-- ScanWriteVolumeIT, 근거: 관측(재현됨)). 7-4 의 창 시나리오에서 배송 하나가 37–94 번 고쳐졌다.
--
-- 편차는 라우트의 사실이다. 여기에 한 번 적고 배송의 ETA 는 읽는 자리에서 planned_arrival + 편차로
-- 계산한다. eta_at 을 남겨 두면 읽는 쪽이 어느 값을 믿을지 모르는 둘째 출처가 된다 — 그래서 지운다.
--
-- 기존 행의 편차는 0 이다. 그 행들의 eta_at − planned_arrival 을 옮겨 오지 않는 이유: 그 값은 스캔마다 달라지는
-- 마지막 전파의 흔적이고 라우트에 하나로 모이지 않는다(배송마다 다를 수 있다). 다음 스캔이 새로 적는다 — 개정
-- 반영이 편차를 0 으로 되돌리는 것과 같은 결과다. 이 표는 route.assigned 의 투영이다(V2 머리말).
--
-- 이 행은 tracking 쓰기 계층의 부모이기도 하다(ADR-070 결정 1) — 스캔과 개정 반영이 배송보다 먼저 잡는다.
-- 인덱스는 넣지 않는다: 이 칸을 조건으로 읽는 질의가 없다(PK 로만 닿는다, 불변규칙 11).
-- =============================================================================

ALTER TABLE route_revisions
  ADD COLUMN deviation_seconds INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN route_revisions.deviation_seconds IS
  '라우트의 편차(초) — 마지막 스캔의 실제 시각 − 계획 시각(DEPARTED_CAMP 는 planned_departure). 음수면 이르다. '
  '배송의 ETA 는 planned_arrival + 이 값이다. 개정 반영(claim)이 0 으로 되돌린다 (ADR-070).';

ALTER TABLE shipments DROP COLUMN eta_at;
