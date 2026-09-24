-- =============================================================================
-- 웨이브 마감 원인 (DESIGN.md §5.2, ADR-054 결정 3)
--
-- 운영자 조기 마감이 생기면서 웨이브를 닫는 쪽이 둘이 되었다. 누가 닫았는지는 **닫는 순간의 사실**로
-- 저장한다. closed_at < cutoff_at + grace 로 파생하면 「스케줄러는 그 전에 닫지 않는다」는 동작과 grace
-- 설정값에 기대게 되고, grace 를 바꾸는 날 과거의 판정이 움직인다 — ADR-028 이 우선도의 근거를 파생값
-- 옆에 저장한 것과 같은 이유다.
--
-- 이 칸이 dawnline_promise_revised_total{cause} 의 출처다(§9.1).
-- =============================================================================

ALTER TABLE waves ADD COLUMN close_cause VARCHAR(16);

-- 백필: 이 마이그레이션 이전에 닫힌 웨이브는 전부 스케줄러가 닫았다 — 수동 경로가 없었다.
-- 행 수는 ADR-023 의 보존(90일 × 하루 40웨이브 ≈ 3,600행)이 상한이라 한 문장으로 충분하다.
UPDATE waves SET close_cause = 'SCHEDULED' WHERE closed_at IS NOT NULL;

ALTER TABLE waves ADD CONSTRAINT ck_waves_close_cause
    CHECK (close_cause IN ('SCHEDULED', 'MANUAL'));

-- 「언제 닫혔나」와 「누가 닫았나」는 함께 있거나 함께 없다. 한쪽만 있는 행은 도메인이 되살리지 못한다
-- (Wave 생성자의 같은 문장) — 그 어긋남을 읽는 시점이 아니라 쓰는 시점에 막는다.
ALTER TABLE waves ADD CONSTRAINT ck_waves_close_cause_with_closed_at
    CHECK ((close_cause IS NULL) = (closed_at IS NULL));
