-- =============================================================================
-- shipments 에 나이의 칸 (DESIGN.md §5.4 「보존」, ADR-058 결정 4)
--
-- 보존은 종결 30일이고 나이는 updated_at 으로 잰다 — created_at 이 아니다. 배정은 오래전이라도
-- 마지막 스캔이 어제 왔으면 조사 대상은 어제 사건이다(ADR-023 과 같은 이유).
--
-- 기존 행은 now() 로 채운다. 그 행들의 진짜 나이는 어디에도 없고, 모르는 나이를 고를 때는 늦게
-- 지우는 쪽을 고른다 — 대가는 첫 30일 동안 기존 행이 정리되지 않는 것뿐이다. 여기서 now() 를
-- 쓰는 것은 불변규칙 12 의 예외가 아니다(V1 의 CURRENT_DATE 와 같은 이유 — 마이그레이션은 서비스
-- 코드가 아니다).
--
-- DEFAULT 는 채운 뒤에 떼어 낸다. 남겨 두면 시계를 주입받지 않은 쓰기가 조용히 DB 시각으로 들어간다
-- — 이 칸은 어댑터(JpaShipmentRepository)가 주입된 시계로 적는 칸이고, 빠뜨리면 NOT NULL 이 소리 낸다.
--
-- 인덱스는 여기서 넣지 않는다. 정리 질의의 계획은 운영 크기(30일치 450만 행)에서 ANALYZE 뒤에 재고
-- 판단을 행 수와 함께 적는다(불변규칙 11, docs/benchmarks/phase7-retention-indexes.md).
-- =============================================================================

ALTER TABLE shipments ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE shipments ALTER COLUMN updated_at DROP DEFAULT;

COMMENT ON COLUMN shipments.updated_at IS
  '값이 바뀐 마지막 쓰기의 시각 — 어댑터가 주입된 시계로 적는다. 보존(종결 30일 · 상한 365일)의 나이다(ADR-058).';
