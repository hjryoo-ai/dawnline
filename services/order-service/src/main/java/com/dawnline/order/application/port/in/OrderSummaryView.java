package com.dawnline.order.application.port.in;

import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderStatus;
import com.dawnline.order.domain.ServiceTier;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 주문 목록의 한 줄 (DESIGN.md §5.1 {@code GET /api/v1/orders}).
 *
 * <p>주소 문자열을 담지 않는다. 목록은 한 번에 여러 건이 나가고, 그 응답이 로그·캐시·프록시를
 * 거치는 경로가 상세 조회보다 넓다. 위치가 필요하면 우편번호와 geohash 까지만 준다(§9.3).
 *
 * @param orderId       주문 id
 * @param status        현재 상태
 * @param serviceTier   서비스 티어
 * @param postalCode    우편번호
 * @param geohash7      7자리 geohash
 * @param promisedStart 약속 배송창 시작
 * @param promisedEnd   약속 배송창 종료
 * @param itemCount     품목 줄 수
 * @param placedAt      접수 시각
 * @param updatedAt     마지막 상태 변경 시각
 */
public record OrderSummaryView(
        UUID orderId,
        OrderStatus status,
        ServiceTier serviceTier,
        String postalCode,
        String geohash7,
        Instant promisedStart,
        Instant promisedEnd,
        int itemCount,
        Instant placedAt,
        Instant updatedAt) {

    public OrderSummaryView {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(status, "status");
    }

    /**
     * @param order 애그리거트
     */
    public static OrderSummaryView of(Order order) {
        Objects.requireNonNull(order, "order");
        return new OrderSummaryView(order.id(), order.status(), order.serviceTier(),
                order.address().postalCode(), order.address().geohash7(),
                order.promisedWindow().start(), order.promisedWindow().end(),
                order.items().size(), order.placedAt(), order.updatedAt());
    }
}
