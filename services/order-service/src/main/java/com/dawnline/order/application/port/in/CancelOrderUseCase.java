package com.dawnline.order.application.port.in;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 주문 취소 (DESIGN.md §5.1 {@code POST /api/v1/orders/{id}/cancel}).
 *
 * <p>{@code PLACED}·{@code PLANNED} 에서만 가능하다. 그 밖의 상태는 409 이며, 그 판정은
 * 상태 머신이 한다(불변규칙 6) — 여기서 상태를 다시 나열하지 않는다.
 */
public interface CancelOrderUseCase {

    /**
     * @param orderId 주문 id
     * @param reason  취소 사유. 없어도 취소는 성립한다
     * @return 취소된 주문 상세
     * @throws com.dawnline.common.error.NotFoundException                그런 주문이 없을 때 (404)
     * @throws com.dawnline.common.error.IllegalStateTransitionException  취소할 수 없는 상태일 때 (409)
     */
    OrderView cancel(UUID orderId, @Nullable String reason);
}
