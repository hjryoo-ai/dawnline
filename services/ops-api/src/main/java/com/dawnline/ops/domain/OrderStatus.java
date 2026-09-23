package com.dawnline.ops.domain;

/**
 * {@code rm_orders.order_status} — 주문 쪽 축 (DESIGN.md §5.5 「DDL 정정」).
 *
 * <p><strong>선언 순서가 진행 순서다</strong>({@link Progress#judge}). 값을 더하거나 옮기면 판정이
 * 함께 바뀐다 — {@code OpsAxesTest} 가 설계서의 표와 이 순서를 대조한다.
 *
 * <p>{@code DELIVERED}·{@code FAILED}(배송) 가 없다 — 그것은 {@link DeliveryOutcome} 의 것이다.
 * 한 칸에 두 출처의 사실을 접지 않는다(ADR-051 결정 2 의 적용, 2026-09-24).
 */
public enum OrderStatus {

    /** {@code order.placed}. */
    PLACED,

    /** {@code fulfillment.planned} 의 {@code outcome=PLANNED}. */
    PLANNED,

    /**
     * {@code fulfillment.planned} 의 {@code outcome=UNSERVICEABLE}. order-service 는 이것을
     * {@code FAILED} 로 두지만 여기서는 계약의 이름을 쓴다 — {@code delivery_outcome} 의
     * {@code FAILED} 와 같은 글자가 다른 뜻으로 앉지 않게 한다.
     */
    UNSERVICEABLE,

    /** {@code order.dispatched}. */
    DISPATCHED,

    /**
     * {@code order.cancelled}. 맨 위인 것은 order-service 의 사실 그대로다 — 취소 이벤트는 그쪽이
     * 취소를 <em>받아들였을 때만</em> 나가고, 그러면 뒤이은 {@code order.dispatched} 는 거기서
     * 거부된다. 그래도 그 주문이 실제로 배송됐다면 그것은 {@link DeliveryOutcome} 칸에 적히고,
     * 두 칸의 조합이 운영자의 예외 목록이 된다.
     */
    CANCELLED
}
