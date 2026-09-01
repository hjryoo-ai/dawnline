/**
 * 디스패치 도메인 — {@code RoutePlan} 상태 머신({@code REQUESTED → PLANNING → PLANNED → PUBLISHED}),
 * 라우트·차량·기사 모델, 룰 엔진 계층 (DESIGN.md §5.3, §6.3). Phase 3 산출물.
 *
 * <p>Spring·JPA 에 의존하지 않는다(CLAUDE.md 불변규칙 5, ArchUnit 규칙 1).
 */
@NullMarked
package com.dawnline.dispatch.domain;

import org.jspecify.annotations.NullMarked;
