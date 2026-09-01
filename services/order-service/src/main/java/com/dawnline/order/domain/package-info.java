/**
 * 주문 도메인 — {@code Order} 애그리거트, {@code DeliveryAddress}·{@code Parcel}·
 * {@code PromisedWindow} 값 객체, 주문 상태 머신 (DESIGN.md §5.1). Phase 1 산출물.
 *
 * <p>Spring·JPA 에 의존하지 않는다(CLAUDE.md 불변규칙 5, ArchUnit 규칙 1).
 * 상태 전이는 {@code order.markDispatched()} 같은 애그리거트 메서드로만 한다(불변규칙 6).
 */
@NullMarked
package com.dawnline.order.domain;

import org.jspecify.annotations.NullMarked;
