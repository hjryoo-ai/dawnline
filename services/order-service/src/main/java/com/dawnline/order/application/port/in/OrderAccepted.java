package com.dawnline.order.application.port.in;

import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderStatus;
import com.dawnline.order.domain.ServiceTier;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 접수 응답 (DESIGN.md §5.1 {@code POST /api/v1/orders} 201).
 *
 * <p>이 레코드는 두 곳에 쓰인다. 하나는 HTTP 응답 본문이고, 다른 하나는
 * {@code idempotency_keys.response_body} 에 저장돼 재요청 때 <strong>그때 준 답 그대로</strong>
 * 재생되는 값이다(§5.1 1단계 → 200). 그래서 필드를 <em>빼거나 뜻을 바꾸는</em> 변경은
 * 24시간 동안 이미 저장된 행과 어긋난다. 추가만 한다.
 *
 * <p>배송지·고객 id 는 담지 않는다. 요청한 쪽이 이미 아는 값이고, 저장되는 값이라 남길 이유가 없다
 * (§9.3 최소 수집).
 *
 * @param orderId        주문 id (UUIDv7)
 * @param status         접수 직후 상태 — 항상 {@link OrderStatus#PLACED}
 * @param serviceTier    서비스 티어
 * @param promisedStart  약속 배송창 시작 (§2.2)
 * @param promisedEnd    약속 배송창 종료
 * @param placedAt       접수 시각
 */
public record OrderAccepted(
        UUID orderId,
        OrderStatus status,
        ServiceTier serviceTier,
        Instant promisedStart,
        Instant promisedEnd,
        Instant placedAt) {

    public OrderAccepted {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(serviceTier, "serviceTier");
        Objects.requireNonNull(promisedStart, "promisedStart");
        Objects.requireNonNull(promisedEnd, "promisedEnd");
        Objects.requireNonNull(placedAt, "placedAt");
    }

    /**
     * 애그리거트에서 응답을 만든다.
     *
     * @param order 접수된 주문
     */
    public static OrderAccepted of(Order order) {
        Objects.requireNonNull(order, "order");
        return new OrderAccepted(order.id(), order.status(), order.serviceTier(),
                order.promisedWindow().start(), order.promisedWindow().end(), order.placedAt());
    }
}
