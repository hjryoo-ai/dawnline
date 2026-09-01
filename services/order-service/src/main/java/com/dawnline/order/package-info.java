/**
 * order-service — 주문 접수·검증·취소와 상태 조회 (DESIGN.md §5.1).
 *
 * <p>피크에 가장 먼저 맞는 서비스라 쓰기 경로를 최소화한다(INSERT 2건 + 커밋, 외부 호출 없음).
 * 내부 구조는 헥사고날(§3.4): {@code adapter → application → domain} 한 방향 의존.
 * 발행 이벤트 {@code order.placed}, {@code order.cancelled} / 구독 이벤트
 * {@code order.dispatched}, {@code delivery.status} (§3.2).
 */
@NullMarked
package com.dawnline.order;

import org.jspecify.annotations.NullMarked;
