package com.dawnline.fulfillment.domain;

import java.util.Set;

/**
 * 웨이브 상태 (DESIGN.md §5.2 Wave 수명주기).
 *
 * <pre>
 * OPEN ──(cutoff+grace 도달, 락 획득)──▶ CLOSING ──(wave.closed 발행 완료)──▶ CLOSED
 *                                                                             ├──(route.assigned)──▶ PLANNED
 *                                                                             └──(plan.failed)─────▶ PLAN_FAILED
 * </pre>
 *
 * <p>{@link FulfillmentOrderStatus} 의 축 규칙을 쓰지 않는다. 주문 상태와 달리 웨이브 전이는 <strong>전부
 * 이 서비스 자신이거나 인과적으로 앞선 사건</strong>이라 순서가 뒤바뀔 수 없다 —
 * {@code OPEN→CLOSING→CLOSED} 는 자기 스케줄러가 하고, 그 뒤의 둘은 우리가 발행한
 * {@code wave.closed} 를 dispatch 가 받아 계획한 결과다. 건너뜀을 수용할 이유가 없다.
 *
 * <h2>마지막 두 전이는 Phase 3 까지 발화하지 않는다</h2>
 * {@code CLOSED → PLANNED/PLAN_FAILED} 를 일으키는 {@code route.assigned}·{@code plan.failed} 의
 * 발행자는 dispatch-service 이고 Phase 3 에 생긴다. <strong>그리고 §4.1 의 소비자 목록에는
 * fulfillment 가 없다</strong> — §5.2 의 수명주기와 §4.1 의 소비자 표가 어긋나 있으며, 그 결정은
 * Phase 3 착수 시점에 필요하다. 그때까지 이 두 상태는 도달 불가능하다.
 *
 * <p>그 사실이 남기는 것: {@code fulfillment_orders} 의 정리 배치는 "소속 웨이브가
 * {@code PLANNED}/{@code PLAN_FAILED}" 인 행을 지우므로(ADR-023), Phase 3 전까지는 {@code PLANNED}
 * 주문 행이 정리 대상이 되지 않는다. 취소·배차 불가 행만 지워진다.
 */
public enum WaveStatus {

    /** 주문을 받는다. */
    OPEN,

    /** 컷오프에 도달해 마감 중. {@code wave.closed} 를 outbox 에 넣는 구간이다. */
    CLOSING,

    /** 마감됐다. 더는 주문을 받지 않는다. */
    CLOSED,

    /** 계획이 끝나 라우트가 생겼다 (Phase 3). */
    PLANNED,

    /** 계획이 실패했다 (Phase 3). */
    PLAN_FAILED;

    /** 이 상태에서 갈 수 있는 다음 상태들. */
    public Set<WaveStatus> allowedTransitions() {
        return switch (this) {
            case OPEN -> Set.of(CLOSING);
            case CLOSING -> Set.of(CLOSED);
            case CLOSED -> Set.of(PLANNED, PLAN_FAILED);
            case PLANNED, PLAN_FAILED -> Set.of();
        };
    }

    /**
     * {@code next} 로 전이할 수 있는가.
     *
     * <p>같은 상태로의 전이는 허용하지 않는다. 컷오프 스케줄러가 이미 {@code CLOSING} 인 웨이브를
     * 다시 잡는 일은 Redis 락({@code lock:wave:{id}})과 상태 조회가 막고, 그래도 여기까지 왔다면
     * 그것은 락이 새고 있다는 뜻이라 조용히 통과시키면 안 된다.
     *
     * @param next 목표 상태
     */
    public boolean canTransitionTo(WaveStatus next) {
        return allowedTransitions().contains(next);
    }

    /** 새 주문을 받을 수 있는가. */
    public boolean acceptsOrders() {
        return this == OPEN;
    }

    /**
     * 마감이 이미 발행된 뒤인가.
     *
     * <p>이 시점 이후의 취소는 {@code waves.order_count} 를 건드리지 않는다 —
     * {@code wave.closed} 가 이미 그 숫자로 나갔기 때문이다 (ADR-022).
     */
    public boolean isClosedOrBeyond() {
        return this != OPEN && this != CLOSING;
    }

    /** 하류가 계획을 끝냈는가. {@code fulfillment_orders} 정리 대상 판정에 쓴다 (ADR-023). */
    public boolean isPlanningSettled() {
        return this == PLANNED || this == PLAN_FAILED;
    }

    /** 더 이상 전이가 없는 종료 상태인가. */
    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }
}
