package com.dawnline.order.adapter.out.messaging;

import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderStatus;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code order.cancelled} v1 페이로드 (contracts/events/order.cancelled.v1.schema.json).
 *
 * <p>{@link OrderPlacedPayload} 와 같은 이유로 애그리거트를 그대로 직렬화하지 않는다 —
 * 컴포넌트 이름이 곧 JSON 키이고, 도메인이 바뀔 때 계약 테스트가 깨져야 한다.
 *
 * @param orderId        취소된 주문
 * @param customerId     고객 id
 * @param previousStatus 취소 직전 상태. {@code PLACED} 인지 {@code PLANNED} 인지에 따라 소비자가 할 일이 다르다
 * @param cancelledAt    취소 시각. 소비자가 처리한 시각이 아니라 사건이 일어난 시각이다 (§4.2)
 * @param reason         취소 사유. 없을 수 있다 — 고객이 사유를 대지 않아도 취소는 성립한다
 */
public record OrderCancelledPayload(
        UUID orderId,
        UUID customerId,
        String previousStatus,
        Instant cancelledAt,
        @Nullable String reason) {

    /** {@code eventType} (§4.1). */
    public static final String EVENT_TYPE = "order.cancelled";

    /** 페이로드 스키마 major 버전. */
    public static final int SCHEMA_VERSION = 1;

    /** {@code outbox_events.aggregate_type} VARCHAR(32). */
    public static final String AGGREGATE_TYPE = "order";

    /** 계약이 정한 사유 최대 길이. 자유 텍스트라 개인정보가 섞이지 않도록 제한한다. */
    public static final int MAX_REASON_LENGTH = 200;

    /**
     * @param order          취소된 주문 (이미 {@code CANCELLED} 다)
     * @param previousStatus 취소 직전 상태
     * @param reason         취소 사유
     */
    public static OrderCancelledPayload of(Order order, OrderStatus previousStatus, @Nullable String reason) {
        return new OrderCancelledPayload(
                order.id(),
                order.customerId(),
                previousStatus.name(),
                // 취소 시각은 애그리거트가 들고 있다 — 상태 머신이 전이하며 기록한 값이다.
                order.updatedAt(),
                reason);
    }
}
