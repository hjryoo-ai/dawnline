package com.dawnline.order.application.port.in;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * 주문 목록 한 페이지 (DESIGN.md §5.1 커서 페이지네이션).
 *
 * <p>전체 건수를 담지 않는다. {@code count(*)} 는 고객당 주문이 쌓일수록 비싸지고, 커서
 * 페이지네이션에서는 쓸 데도 없다 — "다음이 있는가" 는 {@code nextCursor} 로 알 수 있다.
 *
 * @param orders     이 페이지의 주문들. 접수 시각 내림차순
 * @param nextCursor 다음 페이지 커서. {@code null} 이면 마지막 페이지다
 */
public record OrderPage(List<OrderSummaryView> orders, @Nullable OrderCursor nextCursor) {

    public OrderPage {
        orders = List.copyOf(Objects.requireNonNull(orders, "orders"));
    }
}
