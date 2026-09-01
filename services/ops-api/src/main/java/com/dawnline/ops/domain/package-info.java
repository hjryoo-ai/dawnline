/**
 * 운영 도메인 — 역할·권한 판단과 감사 기록 규칙 (DESIGN.md §5.5). Phase 6 산출물.
 *
 * <p>읽기 모델({@code rm_*}) 자체는 도메인이 아니라 {@code adapter.out.persistence} 의
 * 프로젝션이다. Spring·JPA 에 의존하지 않는다(불변규칙 5).
 */
@NullMarked
package com.dawnline.ops.domain;

import org.jspecify.annotations.NullMarked;
