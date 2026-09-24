-- 대시보드의 「취소됐는데 배송됨」 — 창 없이 전부 (DESIGN.md §5.5 「조회」, 2026-09-24).
-- 해소(환불·회수)를 기록하는 칸이 없으므로 한 번 들어온 행은 목록에서 나가지 않는다 — 창으로 자르면 처리되지 않은
-- 건이 조용히 사라진다. 술어를 만족하는 행은 드물다(측정의 분포 0.09%) — 부분 인덱스는 그 행만 담는다.
-- 술어의 두 칸은 질의에서 리터럴로 적는다(CLAUDE.md 「부분 인덱스의 술어 컬럼은 리터럴」).
-- 키는 캠프 하나다: 질의가 전체 수(count(*) OVER ())를 함께 읽어 캠프의 행을 어차피 전부 읽으므로 정렬 칸은
-- 값을 하지 않는다(docs/benchmarks/phase6-ops-read-surface.md).
CREATE INDEX ix_rmo_cancelled_delivered ON rm_orders (camp_id)
  WHERE order_status = 'CANCELLED' AND delivery_outcome = 'COMPLETED';
