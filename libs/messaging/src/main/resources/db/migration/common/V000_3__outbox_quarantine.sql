-- =============================================================================
-- outbox_events 발행 측 격리 (DESIGN.md §4.6 "발행 측 실패", §5.1 DDL, ADR-015)
--
-- 배경: 봉투로 만들 수 없는 행(결정적 실패)이 하나 있으면, 릴레이가 created_at 순서로
-- 그 행을 계속 다시 집어 그 뒤의 모든 이벤트가 영구히 나가지 못했다(head-of-line blocking).
-- 이제 결정적 실패는 failed_at 을 찍어 격리하고 릴레이 조회 대상에서 빼며, 뒤의 행은 계속 발행된다.
-- 일시적 실패(브로커 다운 등)는 격리하지 않는다 — 기다리면 풀리기 때문이다.
--
-- 복구는 수동이다(RB-05):
--   UPDATE outbox_events SET failed_at = NULL, publish_attempts = 0 WHERE id = '...';
-- =============================================================================

ALTER TABLE outbox_events
  ADD COLUMN publish_attempts SMALLINT     NOT NULL DEFAULT 0,
  ADD COLUMN failed_at        TIMESTAMPTZ;

-- 릴레이의 조회 경로에서 격리 행을 뺀다. 인덱스 조건이 바뀌므로 다시 만든다.
-- (CLAUDE.md 불변규칙 11: 설계서 §5.1 에 명시된 인덱스이며 새로 추가하는 것이 아니라 조건 변경이다)
DROP INDEX ix_outbox_unpublished;
CREATE INDEX ix_outbox_unpublished ON outbox_events (created_at)
    WHERE published_at IS NULL AND failed_at IS NULL;

COMMENT ON COLUMN outbox_events.publish_attempts IS '발행 실패 횟수. 결정적·일시적 실패 모두에서 증가한다. 자동 폐기 트리거로 쓰지 않는다(ADR-015: 이벤트 소실 금지).';
COMMENT ON COLUMN outbox_events.failed_at IS '격리 시각. NOT NULL 이면 결정적 실패로 격리된 행이라 릴레이가 집지 않는다. 원인 수정 후 NULL 로 되돌리면 재발행된다(RB-05).';
