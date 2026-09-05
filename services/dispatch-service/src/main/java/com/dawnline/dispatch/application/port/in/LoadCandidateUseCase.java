package com.dawnline.dispatch.application.port.in;

/**
 * {@code fulfillment.planned} 를 후보로 적재한다 (DESIGN.md §5.3).
 *
 * <p>이벤트의 스냅샷을 그대로 저장한다 — 계획에 필요한 것은 전부 페이로드에 있어야 하고,
 * fulfillment 에 되묻지 않는다(불변규칙 4).
 */
public interface LoadCandidateUseCase {

    /**
     * @param snapshot 적재할 스냅샷
     * @return 처리 결과
     */
    Outcome load(PlannedOrderSnapshot snapshot);

    /** 처리 결과. */
    enum Outcome {
        /** 새로 적재했다. */
        LOADED,
        /** 이미 있어 아무것도 하지 않았다. */
        DUPLICATE,
        /** 배차 불가로 종결된 주문이라 후보가 아니다. */
        NOT_A_CANDIDATE
    }
}
