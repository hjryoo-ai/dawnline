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
        /**
         * 취소가 먼저 와 표식을 남긴 주문이다 — 적재하지 않았다(ADR-074 결정 2). 어댑터가 이 결과를
         * {@code dawnline_event_rejected_total{reason="cancelled_before_candidate"}} 로 번역한다.
         */
        CANCELLED_FIRST,
        /** 배차 불가로 종결된 주문이라 후보가 아니다. */
        NOT_A_CANDIDATE
    }
}
