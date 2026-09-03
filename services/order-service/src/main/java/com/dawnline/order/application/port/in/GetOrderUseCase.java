package com.dawnline.order.application.port.in;

import java.util.UUID;

/** 주문 상세 조회 (DESIGN.md §5.1 {@code GET /api/v1/orders/{id}}). */
@FunctionalInterface
public interface GetOrderUseCase {

    /**
     * @param orderId 주문 id
     * @return 주문 상세
     * @throws com.dawnline.common.error.NotFoundException 그런 주문이 없을 때 (404)
     */
    OrderView get(UUID orderId);
}
