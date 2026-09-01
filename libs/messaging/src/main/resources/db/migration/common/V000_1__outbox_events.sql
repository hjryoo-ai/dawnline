-- =============================================================================
-- outbox_events — 모든 서비스가 공유하는 트랜잭셔널 아웃박스 (DESIGN.md §5.1, §4.4)
--
-- 이 스크립트는 libs/messaging.jar 안에 들어 있고, 서비스는 Flyway locations 에
-- classpath:db/migration/common 을 추가해서 쓴다. 자세한 규칙은 같은 디렉터리의 README.md 참고.
--
-- 버전 접두어 V000_x 는 서비스 마이그레이션(V1, V2, ...)보다 항상 먼저 오게 하려는 것이다.
-- Flyway 는 버전을 부분별 숫자로 비교하므로 000.1 < 000.2 < 1 이 된다.
-- =============================================================================

CREATE TABLE outbox_events (
  id             UUID PRIMARY KEY,                 -- UUIDv7. 봉투의 eventId 이자 소비 멱등 키 (§4.4)
  aggregate_type VARCHAR(32) NOT NULL,
  aggregate_id   UUID NOT NULL,
  event_type     VARCHAR(64) NOT NULL,
  topic          VARCHAR(96) NOT NULL,
  partition_key  VARCHAR(64) NOT NULL,             -- §4.5 순서 보장의 근거
  headers        JSONB NOT NULL,                   -- traceparent / eventType / schemaVersion (§4.2)
  payload        JSONB NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL,             -- 봉투의 occurredAt (발행 시각이 아니라 사건 시각)
  published_at   TIMESTAMPTZ                       -- NULL 이면 미발행
);

-- 릴레이의 유일한 조회 경로. 부분 인덱스라 발행 완료 행은 인덱스에 남지 않는다 —
-- 미발행이 0건에 가까운 정상 상태에서 인덱스가 사실상 비어 있다는 뜻이다.
-- (CLAUDE.md 불변규칙 11: 설계서에 명시된 인덱스 외에는 추가하지 않는다)
CREATE INDEX ix_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;

COMMENT ON TABLE  outbox_events IS 'Transactional Outbox (DESIGN.md §4.4). 도메인 변경과 같은 트랜잭션에서 INSERT 되고, OutboxRelay 가 FOR UPDATE SKIP LOCKED 로 집어 Kafka 로 발행한다.';
COMMENT ON COLUMN outbox_events.id IS 'UUIDv7. 봉투의 eventId 이며 processed_events 의 멱등 키로 그대로 쓰인다.';
COMMENT ON COLUMN outbox_events.created_at IS '도메인 사건 발생 시각. 봉투의 occurredAt 이 된다(발행 시각 아님, §4.2).';
COMMENT ON COLUMN outbox_events.published_at IS 'Kafka 발행 완료 시각. NULL 이면 미발행. 발행 후 7일 지난 행은 배치 삭제한다(§7.1).';
