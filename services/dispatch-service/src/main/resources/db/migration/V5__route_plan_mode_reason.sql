-- 열화는 왜 일어났는가 (ADR-034, DESIGN.md §6.7).
--
-- `mode` 는 이미 있다 — FULL 인가 FAST 인가. 없는 것은 **왜**다. §6.7 의 자동 전환에는 조건이
-- 둘(컨슈머 랙 · 직전 계획 시간)이고, 운영자가 손으로 지정한 FAST 도 같은 컬럼에 FAST 로 남는다.
-- 셋을 구별하지 못하면 "이 웨이브는 왜 개선 단계를 건너뛰었나" 에 답할 수 없다.
--
-- **카운터 라벨로는 답이 되지 않는다.** `dawnline_plan_degraded_total{camp,reason}` 은 집계이고,
-- 여기서 필요한 것은 개별 답이다 — §6.3 이 라우트에 "왜 이 차인가" 를 남기게 한 것과 같은 요구다.
--
-- 값: REQUESTED | LAG | BUDGET | LAG_UNKNOWN | NONE.
-- LAG_UNKNOWN 이 NONE 과 따로 있는 이유는 **모름이 아니오가 아니기** 때문이다. 랙을 못 본 채
-- 내린 FULL 과 두 조건을 다 보고 내린 FULL 을 한 값으로 접으면, 판단이 조용히 멈춘 것이 정상과
-- 구별되지 않는다(ADR-027 이 리더 락에 세 상태를 둔 것과 같은 규칙).
--
-- NULL 을 허용한다 — 이 컬럼이 생기기 전의 계획은 근거를 남긴 적이 없고, 그것이 사실이다.
-- 기본값을 주면 과거의 계획이 전부 "그 사유로 돌았다" 고 말하게 된다.

ALTER TABLE route_plans
  ADD COLUMN mode_reason VARCHAR(16);

COMMENT ON COLUMN route_plans.mode_reason IS
  '이 계획이 그 mode 로 돈 근거 (ADR-034). REQUESTED 는 사람의 선택이라 열화가 아니다.';
