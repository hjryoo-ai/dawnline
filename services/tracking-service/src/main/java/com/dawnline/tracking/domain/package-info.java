/**
 * 추적 도메인 — {@code Shipment} 애그리거트와 배송 상태 머신
 * ({@code SCHEDULED → OUT_FOR_DELIVERY → ARRIVED → COMPLETED | FAILED}), ETA·at-risk 규칙
 * (DESIGN.md §5.4). Phase 5 산출물.
 *
 * <p>역행 이벤트는 도메인에서 거부한다(멱등). Spring·JPA 에 의존하지 않는다(불변규칙 5).
 */
@NullMarked
package com.dawnline.tracking.domain;

import org.jspecify.annotations.NullMarked;
