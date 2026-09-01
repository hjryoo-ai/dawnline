/**
 * JPA 아웃바운드 어댑터 — {@code fulfillment_centers}, {@code camps}, {@code zones},
 * {@code inventory_stock}, {@code waves}, {@code wave_orders} 매핑 (DESIGN.md §5.2).
 *
 * <p>자기 서비스 DB({@code dawnline_fulfillment})만 접근한다(CLAUDE.md 불변규칙 3).
 */
@NullMarked
package com.dawnline.fulfillment.adapter.out.persistence;

import org.jspecify.annotations.NullMarked;
