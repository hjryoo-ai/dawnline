/**
 * Redis 아웃바운드 어댑터 — 멱등 키 {@code idem:order:*}, 고객 레이트 리밋
 * {@code rl:customer:*} (DESIGN.md §7.2).
 *
 * <p>Redis 는 진실 저장소가 아니다(CLAUDE.md 불변규칙 7). 키가 사라져도 DB
 * {@code idempotency_keys} 만으로 정확성이 유지되는 폴백 경로를 함께 만든다.
 */
@NullMarked
package com.dawnline.order.adapter.out.redis;

import org.jspecify.annotations.NullMarked;
