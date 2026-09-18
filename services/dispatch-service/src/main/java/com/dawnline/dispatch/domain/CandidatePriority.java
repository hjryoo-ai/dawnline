package com.dawnline.dispatch.domain;

import com.dawnline.common.error.ValidationException;

/**
 * 후보 우선도의 점수표 (DESIGN.md §6.3, [ADR-028]).
 *
 * <h2>우선순위는 선언이 아니라 파생이다</h2>
 * 계약에 {@code priority} 필드를 넣는 길은 두 가지로 막힌다. <strong>클라이언트 값은 신뢰할 수
 * 없고</strong>(고객 API 는 무인증이다 — §10, {@code customerId} 조차 클라이언트 주장값이다),
 * <strong>티어에서 파생하면 상수가 된다</strong> — 웨이브가 (캠프, 티어, 컷오프) 단위라 한 계획
 * 안의 모든 후보가 같은 티어다. 아무것도 가르지 못하는 값은 우선순위가 아니다.
 *
 * <p>한 계획 <em>안에서</em> 실제로 달라야 하는 것은 <strong>"이 주문을 우리가 이미 얼마나
 * 실망시켰는가"</strong> 이고, 그것은 dispatch 가 후보 적재 시점에 <em>이미 받은 사실</em>이다.
 *
 * <h2>점수표는 코드가 아니라 데이터다</h2>
 * 룰(§6.3)이 DB 에 있는 것과 같은 이유다 — 가중치는 정책이고, 정책을 바꾸는 데 배포가 필요하면
 * 그것은 바꿀 수 없는 정책이다. 여기 기본값을 두지 않는 것도 같은 이유다({@code PlanningBudget}
 * 과 같은 규칙): 도메인이 기본값을 알면 설정을 안 읽어도 돌아가서 설정이 죽은 코드가 된다.
 *
 * @param promiseRevisedWeight 약속을 한 번 개정한 주문에 더하는 점수 (ADR-020 의 개정은 깨진 약속이다)
 * @param requiresColdWeight   냉장 주문에 더하는 점수 (미배정의 대가가 다른 주문보다 크다, §6.3 cold-chain)
 */
public record CandidatePriority(int promiseRevisedWeight, int requiresColdWeight) {

    public CandidatePriority {
        if (promiseRevisedWeight < 0 || requiresColdWeight < 0) {
            throw ValidationException.field("dawnline.dispatch.priority",
                    promiseRevisedWeight + "/" + requiresColdWeight,
                    "우선도 가중치는 음수일 수 없습니다 — 점수는 실망의 누적이지 감점이 아닙니다");
        }
    }

    /**
     * 이 후보의 우선도.
     *
     * @param promiseRevised 약속이 개정됐는가
     * @param requiresCold   냉장이 필요한가
     */
    public int scoreOf(boolean promiseRevised, boolean requiresCold) {
        return (promiseRevised ? promiseRevisedWeight : 0)
                + (requiresCold ? requiresColdWeight : 0);
    }
}
