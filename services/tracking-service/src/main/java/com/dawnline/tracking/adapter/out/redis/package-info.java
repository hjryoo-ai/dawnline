/**
 * Redis 아웃바운드 어댑터 — 기사 위치 {@code driver:*:pos}(GEO), at-risk 쿨다운
 * {@code route:*:atrisk:cooldown} (DESIGN.md §7.2).
 *
 * <p>쿨다운 키가 사라지면 at-risk 가 중복 발행될 수 있지만, 소비자 멱등 처리가 흡수한다(불변규칙 7).
 */
@NullMarked
package com.dawnline.tracking.adapter.out.redis;

import org.jspecify.annotations.NullMarked;
