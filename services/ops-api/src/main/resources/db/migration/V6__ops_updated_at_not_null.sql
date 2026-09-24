-- =============================================================================
-- 읽기 모델 세 표에 나이의 칸 (DESIGN.md §5.5 「updated_at」 · §7.1 보존 표, ADR-058 결정 4)
--
-- 보존은 90일이고 나이는 updated_at 으로 잰다 — 마지막으로 행을 만진 시각이다. rm_orders 에는 칸이 있었지만
-- NULL 을 허용했고, 행을 키만으로 만드는 lock 의 INSERT 는 그 칸을 비워 두었다 — 패치가 빈 채로 끝나면 그 행은
-- 끝까지 나이가 없었다. rm_waves · rm_routes 에는 칸이 없었다. 이제 세 표 모두 NOT NULL 이고, lock 의 INSERT 가
-- 적는다.
--
-- 이 칸은 사실이 아니라 프로젝션의 기록이다 — V1 머리말의 규칙 1·2(「키 말고는 NULL 허용」 · 「DEFAULT 를 두지
-- 않는다」)는 사실의 칸에 대한 것이다. 사실은 먼저 온 이벤트가 채우므로 비어 있을 수 있지만, 행을 만진 시각은
-- 행이 생기는 순간 있다.
--
-- 기존 행은 now() 로 채운다. 진짜 나이는 어디에도 없고, 모르는 나이를 고를 때는 늦게 지우는 쪽을 고른다 —
-- 대가는 첫 90일 동안 기존 행이 정리되지 않는 것뿐이다. now() 는 불변규칙 12 의 예외가 아니다(마이그레이션은
-- 서비스 코드가 아니다). DEFAULT 는 채운 뒤에 떼어 낸다: 남겨 두면 시계를 주입받지 않은 쓰기가 조용히 DB 시각으로
-- 들어가고, 떼어 두면 그 쓰기가 NOT NULL 에서 소리 낸다.
--
-- 인덱스는 여기서 넣지 않는다. 정리 질의의 계획은 운영 크기(rm_orders 90일치 1,350만 행)에서 ANALYZE 뒤에 재고
-- 판단을 행 수와 함께 적는다(불변규칙 11, docs/benchmarks/phase7-retention-indexes.md).
-- =============================================================================

UPDATE rm_orders SET updated_at = now() WHERE updated_at IS NULL;
ALTER TABLE rm_orders ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE rm_waves ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE rm_waves ALTER COLUMN updated_at DROP DEFAULT;

ALTER TABLE rm_routes ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE rm_routes ALTER COLUMN updated_at DROP DEFAULT;

COMMENT ON COLUMN rm_orders.updated_at IS
  '프로젝션이 마지막으로 행을 만진 시각 — 사실이 아니다. 보존(종결 90일 · 상한 365일)의 나이다(ADR-058).';
COMMENT ON COLUMN rm_waves.updated_at IS
  '프로젝션이 마지막으로 행을 만진 시각. 보존 90일의 나이 — 참조하는 rm_orders 가 없을 때만 지운다(ADR-058).';
COMMENT ON COLUMN rm_routes.updated_at IS
  '프로젝션이 마지막으로 행을 만진 시각. 보존 90일의 나이 — 참조하는 rm_orders 가 없을 때만 지운다(ADR-058).';
