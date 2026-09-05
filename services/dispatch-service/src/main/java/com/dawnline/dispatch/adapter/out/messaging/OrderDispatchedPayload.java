package com.dawnline.dispatch.adapter.out.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code order.dispatched.v1} 페이로드.
 *
 * <p>order-service 가 이것을 받아 주문을 {@code DISPATCHED} 로 전이한다(§5.1). 취소된 주문에
 * 이 이벤트가 도착하는 것은 <strong>설계된 경합 창</strong>이고 버그가 아니다
 * (ADR-017 후속 정정) — 그 창을 닫는 쪽은 §6.10 이다.
 *
 * @param orderId      주문 id (파티션 키와 같아야 한다)
 * @param routeId      배정된 라우트
 * @param dispatchedAt 배정 시각
 */
public record OrderDispatchedPayload(UUID orderId, UUID routeId, String dispatchedAt) {

    /** {@code eventType}. */
    public static final String EVENT_TYPE = "order.dispatched";

    /** 페이로드 스키마 major. */
    public static final int SCHEMA_VERSION = 1;

    /** {@code outbox_events.aggregate_type}. */
    public static final String AGGREGATE_TYPE = "Order";

    /**
     * @param orderId      주문 id
     * @param routeId      라우트 id
     * @param dispatchedAt 배정 시각
     */
    public static OrderDispatchedPayload of(UUID orderId, UUID routeId, Instant dispatchedAt) {
        return new OrderDispatchedPayload(orderId, routeId, dispatchedAt.toString());
    }
}
