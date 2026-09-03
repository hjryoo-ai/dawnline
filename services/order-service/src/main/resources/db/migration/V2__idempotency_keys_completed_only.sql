-- =============================================================================
-- idempotency_keys: 완료 기록만 남긴다 (ADR-019)
--
-- ADR-018 이후 이 테이블에는 완료된 요청만 들어간다. 행이 있다는 것이 곧 "그 요청은 끝났고
-- 응답은 이것" 이므로 status 컬럼은 값이 하나뿐이고, 응답 두 컬럼은 비어 있을 수 없다.
--
-- V1 을 고치지 않고 V2 를 쌓는 이유: V1 은 이미 main 에 머지됐다. 머지된 마이그레이션은 불변이다
-- (CLAUDE.md). "아직 배포된 적 없으니까" 는 그 규칙을 무너뜨리는 전형적인 첫 예외이고, 당장
-- 이전 V1 을 적용해 둔 로컬 볼륨에서 Flyway 체크섬 불일치로 드러난다.
-- =============================================================================

-- 남아 있는 IN_PROGRESS 행은 처리 도중 죽은 프로세스의 흔적이다. 그 행은 해당 멱등 키를
-- expires_at 까지 영구히 막으므로(ADR-018 맥락), 지우는 것이 곧 복구다.
DELETE FROM idempotency_keys
 WHERE status <> 'DONE' OR response_code IS NULL OR response_body IS NULL;

ALTER TABLE idempotency_keys DROP COLUMN status;
ALTER TABLE idempotency_keys ALTER COLUMN response_code SET NOT NULL;
ALTER TABLE idempotency_keys ALTER COLUMN response_body SET NOT NULL;

-- 보존 7일 정리 배치용. PK 는 idem_key 라 expires_at 범위 삭제를 돕지 못한다.
-- EXPLAIN 비교: docs/benchmarks/phase1-idempotency-cleanup-index.md (불변규칙 11)
CREATE INDEX ix_idempotency_keys_cleanup ON idempotency_keys (expires_at);

COMMENT ON COLUMN idempotency_keys.request_hash IS '같은 키에 다른 요청이 오면 422 로 거부하기 위한 해시. 원문 바이트가 아니라 요청 표준형을 해싱한다 — 공백·필드 순서만 다른 재전송이 422 가 되면 안 된다 (§5.1, ADR-018).';
COMMENT ON COLUMN idempotency_keys.expires_at IS 'created_at + 7일 (ADR-019). 이 시각이 지나면 정리 배치가 지우고, 그 뒤 같은 멱등 키는 재생이 아니라 새 주문이 된다 — 클라이언트와의 계약이다. Redis 키 TTL 24h 와 다른 이유: 24시간은 DB 를 읽지 않고 중복을 거르는 구간이고, 이후 7일까지는 DB 가 답한다.';
