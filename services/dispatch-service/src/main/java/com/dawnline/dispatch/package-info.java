/**
 * dispatch-service — 후보 적재, 룰 엔진, 비용 기반 경로 최적화, 라우트 확정·재계획 (DESIGN.md §5.3, §6).
 *
 * <p>발행 이벤트 {@code route.assigned}, {@code order.dispatched}, {@code plan.failed} /
 * 구독 이벤트 {@code fulfillment.planned}, {@code wave.closed}, {@code delivery.at-risk} (§3.2).
 */
@NullMarked
package com.dawnline.dispatch;

import org.jspecify.annotations.NullMarked;
