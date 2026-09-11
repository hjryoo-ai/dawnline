-- 우선순위는 선언이 아니라 파생이다 (ADR-028, DESIGN.md §5.3 · §6.3).
--
-- dispatch 는 `fulfillment.planned` 에서 이미 두 사실을 받는다 — 약속이 개정됐는가
-- (ADR-020: 개정은 <한 번 깬 약속>이다), 냉장이 필요한가(미배정의 대가가 크다).
-- `priority` 는 그 사실들에 점수표를 적용한 값이고, 점수표는 설정
-- (`dawnline.dispatch.priority.*`)에 있다.
--
-- **파생값(priority)과 함께 사실(promise_revised)도 남긴다.** 둘 중 하나만으로는 부족하다:
--   - 사실만 두면 점수표를 바꿀 때 <계획 중인 웨이브>의 우선도가 흔들린다. §6.3 은 계획이
--     시작 시점 스냅샷으로 돈다고 정했고, 파생값을 저장해 두는 것이 그 스냅샷이다.
--   - 파생값만 두면 "왜 이 주문이 우선인가" 에 답할 수 없고, 점수표를 고쳐도 이미 적재된
--     후보를 다시 뽑을 방법이 없다.
-- `requires_cold` 는 이미 이 표에 있으므로 새로 필요한 것은 이 컬럼 하나다.
--
-- 기본값 FALSE 로 붙인다 — 기존 행은 개정된 적이 없는 주문이고, 그것이 사실이다.

ALTER TABLE dispatch_candidates
  ADD COLUMN promise_revised BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN dispatch_candidates.promise_revised IS
  'fulfillment.planned.promiseRevised 스냅샷. priority 의 근거이지 결과가 아니다 (ADR-028).';
