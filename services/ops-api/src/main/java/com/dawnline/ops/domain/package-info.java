/**
 * 운영 도메인 (DESIGN.md §5.5).
 *
 * <p>읽기 모델({@code rm_*}) 자체는 도메인이 아니라 {@code adapter.out.persistence} 의 프로젝션이다.
 * 여기 있는 것은 그 프로젝션이 <strong>판정</strong>할 때 쓰는 순수 함수다 — 상태 칸 넷의 진행 축과
 * 개정·시각 비교(ADR-051
 * 결정 3: 판정은 {@code (현재 값, 들어온 값)} 의 순수 함수여야 한다). Spring·JPA 에 의존하지
 * 않는다(불변규칙 5).
 */
@NullMarked
package com.dawnline.ops.domain;

import org.jspecify.annotations.NullMarked;
