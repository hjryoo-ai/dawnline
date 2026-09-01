-- =============================================================================
-- processed_events — 소비 멱등 (DESIGN.md §5.1, §4.4, §8.5)
--
-- 모든 Kafka 리스너는 비즈니스 로직 "전에" 이 테이블을 선점한다 (CLAUDE.md 불변규칙 2).
-- 선점·비즈니스 로직·자기 outbox 기록이 하나의 트랜잭션이다.
-- =============================================================================

CREATE TABLE processed_events (
  event_id     UUID NOT NULL,                      -- 봉투의 eventId (UUIDv7)
  consumer     VARCHAR(64) NOT NULL,               -- 소비자 이름. 인스턴스마다 달라지면 안 된다.
  processed_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (event_id, consumer)
);

COMMENT ON TABLE  processed_events IS '소비 멱등 기록 (DESIGN.md §4.4). IdempotentConsumer 가 INSERT ... ON CONFLICT DO NOTHING 으로 선점한다.';
COMMENT ON COLUMN processed_events.consumer IS '같은 이벤트를 여러 소비자가 각자 한 번씩 처리할 수 있게 하는 두 번째 키. 스케일아웃해도 값이 같아야 한다.';
