package com.dawnline.order.application.port.in;

/** 주문 목록 조회 (DESIGN.md §5.1 {@code GET /api/v1/orders}). */
@FunctionalInterface
public interface ListOrdersUseCase {

    /**
     * @param query 조회 조건
     * @return 한 페이지와 다음 커서
     */
    OrderPage list(ListOrdersQuery query);
}
