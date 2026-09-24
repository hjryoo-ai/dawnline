-- 캠프 코드 — wave.closed 의 campCode 스냅샷 (DESIGN.md §5.3 「캠프 코드」 · §5.5 「조회」, 2026-09-24).
-- 운영자는 캠프를 UUID 가 아니라 코드로 부른다. 계약에서 선택이라(같은 major 안의 추가) 그 전의 이벤트로 만든
-- 행은 NULL 이다 — 부재는 값이 아니다(ADR-051). 길이는 fulfillment 의 camps.code 와 같다.
ALTER TABLE rm_waves ADD COLUMN camp_code VARCHAR(16);
