/**
 * JPA 아웃바운드 어댑터 — {@code orders}, {@code order_items}, {@code idempotency_keys} 매핑 (DESIGN.md §5.1).
 *
 * <p>JPA 어노테이션은 도메인 모델이 아니라 이 패키지의 엔티티에만 둔다(§3.4).
 * 자기 서비스 DB({@code dawnline_order})만 접근한다(CLAUDE.md 불변규칙 3).
 */
@NullMarked
package com.dawnline.order.adapter.out.persistence;

import org.jspecify.annotations.NullMarked;
