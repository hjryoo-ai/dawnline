/**
 * 풀필먼트 도메인 — FC·캠프·권역 모델과 {@code Wave} 애그리거트·웨이브 상태 머신
 * ({@code OPEN → CLOSING → CLOSED → PLANNED}, DESIGN.md §5.2). Phase 2 산출물.
 *
 * <p>Spring·JPA 에 의존하지 않는다(CLAUDE.md 불변규칙 5, ArchUnit 규칙 1).
 */
@NullMarked
package com.dawnline.fulfillment.domain;

import org.jspecify.annotations.NullMarked;
