package com.dawnline.sim.order;

import java.util.UUID;

/**
 * 주문 취소 — 고객의 {@code POST /api/v1/orders/{orderId}/cancel} (§5.1, 7-4 turbulent).
 *
 * <p>{@link OrderClient} 와 나눈 이유: 접수만 하는 시나리오와 테스트가 람다 하나로 클라이언트를 만든다. 취소는 그 위에 얹는 일이다.
 */
@FunctionalInterface
public interface OrderCancelClient {

    /**
     * 취소를 보낸다. 실패도 값으로 돌려준다 — 409 는 이 도구가 재는 것이다({@code DISPATCHED} 뒤의 취소, A18 의 창).
     *
     * @param orderId 주문 id
     * @return 응답
     */
    OrderClient.Response cancel(UUID orderId);
}
