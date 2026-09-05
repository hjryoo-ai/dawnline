package com.dawnline.fulfillment.adapter.in.messaging;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code order.cancelled.v1} 페이로드 (§4.3).
 *
 * <p>fulfillment 가 쓰는 것은 {@code orderId} 와 {@code cancelledAt} 뿐이다. 나머지는 계약을
 * 그대로 받기 위해 두었다 — 역직렬화에서 알 수 없는 필드를 만나면 실패하는 설정이라면 여기서
 * 터지고, 그것이 계약이 바뀌었다는 신호다.
 *
 * @param orderId        주문 id
 * @param customerId     고객 id
 * @param previousStatus 취소 직전 상태 ({@code PLACED} 또는 {@code PLANNED})
 * @param cancelledAt    취소 시각
 * @param reason         사유 (선택)
 */
public record OrderCancelledPayload(
        UUID orderId,
        UUID customerId,
        String previousStatus,
        Instant cancelledAt,
        @Nullable String reason) {
}
