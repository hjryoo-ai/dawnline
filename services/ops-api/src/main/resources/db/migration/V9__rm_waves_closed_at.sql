-- 마감 시각 — wave.closed 의 closedAt (DESIGN.md §5.5 「마감 시각」 · 2026-09-27, ADR-067 결정 7).
-- 계약의 필수 필드이고 새 출처가 아니다. cutoff_at + grace 로 파생하지 않는다 — 조기 마감에서 틀리고 grace 설정의
-- 사본이다(ADR-054 가 close_cause 를 저장한 것과 같은 이유). 키 계열(먼저 온 것이 남는다)이고, 이 칸 이전의 이벤트로
-- 만든 행은 NULL 이다 — 부재는 값이 아니다(ADR-051). 첫 소비자는 시뮬레이터의 시간 예산(증차가 마감 뒤면 실패).
ALTER TABLE rm_waves ADD COLUMN closed_at TIMESTAMPTZ;
