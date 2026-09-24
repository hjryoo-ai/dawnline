-- =============================================================================
-- ops-api — 웨이브의 창고 좌표 (DESIGN.md §5.5 「조회」, 묶음 C)
--
-- 라우트는 창고에서 출발해 창고로 돌아온다. 창고 없는 지도는 첫 구간과 마지막 구간을 지운 그림이고,
-- ops-web 의 지도는 이 좌표를 원점으로 그린다(타일이 없어도 — ADR-057).
--
-- 새 출처가 아니다: wave.closed 가 이미 싣는 사실(depot, 계약 필수)이다. 캠프는 fulfillment 의 참조
-- 데이터이고 불변규칙 4 가 동기 호출을 막으므로 계획을 촉발하는 이벤트가 스냅샷으로 싣는다.
--
-- 키 계열이다(ColumnFamily.KEY) — 웨이브의 불변 속성이고 먼저 온 것이 남는다. 없으면 NULL 로 둔다:
-- 부재는 값이 아니다(ADR-051). wave.closed 가 아직 오지 않았으면 화면은 stop 들의 중심으로 물러난다.
-- 좌표는 NUMERIC(9,6) 이다(불변규칙 9).
-- =============================================================================

ALTER TABLE rm_waves
  ADD COLUMN depot_lat NUMERIC(9,6),   -- wave.closed 의 depot.lat
  ADD COLUMN depot_lng NUMERIC(9,6),   -- wave.closed 의 depot.lng
  -- 한 사실의 두 칸이라 함께 오고 함께 없다. 하나만 있는 행은 어느 핸들러가 반쪽을 쓴 결함이다.
  ADD CONSTRAINT ck_rmw_depot_pair CHECK ((depot_lat IS NULL) = (depot_lng IS NULL));
