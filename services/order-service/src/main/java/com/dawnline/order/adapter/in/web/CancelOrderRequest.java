package com.dawnline.order.adapter.in.web;

import com.dawnline.order.adapter.out.messaging.OrderCancelledPayload;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * {@code POST /api/v1/orders/{id}/cancel} 요청 본문 (DESIGN.md §5.1).
 *
 * <p>본문 자체가 선택이다 — 사유 없이 취소하는 것이 정상 경로다.
 *
 * @param reason 취소 사유. 자유 텍스트라 개인정보가 섞이지 않도록 길이를 제한한다(계약과 같은 200자)
 */
public record CancelOrderRequest(@Nullable @Size(max = OrderCancelledPayload.MAX_REASON_LENGTH) String reason) {
}
