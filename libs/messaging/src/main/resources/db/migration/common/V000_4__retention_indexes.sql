-- =============================================================================
-- 보존·게이지 인덱스 (DESIGN.md §4.4 processed_events 14일, §5.1 DDL, §9.1)
--
-- 두 인덱스 모두 설계서 §5.1 DDL 에 명시돼 있다 (CLAUDE.md 불변규칙 11 —
-- 설계서를 먼저 고치고 여기로 온다). EXPLAIN 비교는 이 마이그레이션을 넣은 PR 에 첨부한다.
-- =============================================================================

-- (1) 격리 게이지용. dawnline_outbox_failed 는 스크레이프 주기(5초)마다
--     `count(*) WHERE failed_at IS NOT NULL` 을 돈다. 부분 인덱스가 없으면 격리 행이
--     0개여도 매번 전체 힙을 훑는다 — 즉 "아무 일도 없을 때" 가 가장 비싼 구조였다.
--     부분 인덱스는 격리된 행만 담으므로 평상시 크기가 0 블록에 가깝다.
CREATE INDEX ix_outbox_failed ON outbox_events (failed_at)
    WHERE failed_at IS NOT NULL;

-- (2) processed_events 정리 배치용 (§4.4 보존 14일).
--     PK 가 (event_id, consumer) 라 processed_at 범위 조건을 전혀 돕지 못한다.
--     정리 배치는 `processed_at < :threshold` 를 오래된 순으로 LIMIT 씩 지우므로
--     이 인덱스가 없으면 매 배치가 풀스캔 + 정렬이 된다.
--     부분 인덱스로 만들 수 없다 — 임계 시각이 실행할 때마다 움직이기 때문이다.
CREATE INDEX ix_processed_events_cleanup ON processed_events (processed_at);

COMMENT ON INDEX ix_outbox_failed IS 'dawnline_outbox_failed 게이지의 count(*) 전용 부분 인덱스 (§9.1).';
COMMENT ON INDEX ix_processed_events_cleanup IS '보존 14일 정리 배치 전용 (§4.4, §7.1).';

-- publish_attempts 의 의미를 §4.6 확정 문구로 맞춘다. V000_3 의 주석은 "결정적·일시적 실패 모두에서
-- 증가한다" 고만 말해, 일시적 실패로 배치가 중단됐을 때 *시도되지 않은 뒤 행들* 은 증가하지 않는다는
-- 점을 담지 못했다. 이미 배포된 마이그레이션은 고치지 않고 여기서 갱신한다.
COMMENT ON COLUMN outbox_events.publish_attempts IS '그 행에 대해 send 가 실제로 시도된 횟수(§4.6). 일시적 실패로 배치가 중단되면 시도되지 않은 뒤 행들은 증가하지 않는다. 브로커 장애의 관측은 이 컬럼이 아니라 outbox_lag_seconds·outbox_unpublished 게이지가 담당한다. 자동 폐기 트리거로 쓰지 않는다(ADR-015: 이벤트 소실 금지).';
