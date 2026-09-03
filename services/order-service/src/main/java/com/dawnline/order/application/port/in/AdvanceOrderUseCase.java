package com.dawnline.order.application.port.in;

import com.dawnline.order.domain.OrderStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 배송 진행 이벤트를 주문 상태에 반영한다 (DESIGN.md §5.1, ADR-017).
 *
 * <p>{@code order.dispatched} 와 {@code delivery.status} 두 리스너가 같은 포트를 쓴다.
 * 둘이 하는 일은 <em>목표 상태가 무엇인가</em> 만 다르고, 순서 뒤바뀜을 판정하는 규칙은 하나이기
 * 때문이다. 규칙이 두 벌이면 한쪽만 고치는 날이 온다.
 */
@FunctionalInterface
public interface AdvanceOrderUseCase {

    /**
     * 주문을 목표 상태로 옮긴다. 이미 지나온 지점이면 옮기지 않는다.
     *
     * @param orderId    주문 id
     * @param target     목표 상태. {@code DISPATCHED}·{@code DELIVERED}·{@code FAILED} 만 받는다
     * @param occurredAt <strong>사건이 일어난 시각</strong>. 리스너가 처리한 시각이 아니다 —
     *                   정시율(§8.1)이 약속창과 이 시각을 비교하므로, 처리 시각을 쓰면 지연 배달이
     *                   그대로 지표 왜곡이 된다
     * @return 적용 결과
     */
    OrderProgress advance(UUID orderId, OrderStatus target, Instant occurredAt);
}
