/**
 * 코어의 운영자 쓰기 커맨드에 거는 내부 토큰 (DESIGN.md §10 셋째 층, ADR-055).
 *
 * <p>기본 거부다 — 모든 {@code POST}·{@code PUT}·{@code PATCH}·{@code DELETE} 는 토큰 대상이고, 면제는
 * {@link com.dawnline.web.internal.UnauthenticatedWrite} 가 붙은 고객·현장 표면뿐이다.
 */
@NullMarked
package com.dawnline.web.internal;

import org.jspecify.annotations.NullMarked;
