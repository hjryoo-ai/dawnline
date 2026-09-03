package com.dawnline.order.adapter.in.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code order.dispatched} v1 중 <strong>order-service 가 읽는 필드만</strong>
 * (contracts/events/order.dispatched.v1.schema.json).
 *
 * <p>계약에 있는 {@code planId}·{@code waveId} 는 여기 없다. 이 서비스가 쓰지 않기 때문이고,
 * {@code EventJson} 이 알 수 없는 필드를 무시하므로(§4.7) 발행자가 필드를 더해도 깨지지 않는다.
 * 소비자가 자기가 읽는 것만 선언하는 것이 소비자 주도 계약의 요점이다.
 *
 * @param orderId      배정된 주문
 * @param routeId      배정된 라우트. 지금은 로그·추적용이며 상태 전이에는 쓰지 않는다
 * @param dispatchedAt 배정 시각. 상태 전이 시각으로 쓴다 — 리스너가 처리한 시각이 아니다
 */
public record OrderDispatchedPayload(UUID orderId, UUID routeId, Instant dispatchedAt) {
}
