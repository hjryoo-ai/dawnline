/**
 * JPA 아웃바운드 어댑터 — {@code vehicles}, {@code drivers}, {@code dispatch_candidates},
 * {@code route_plans}, {@code routes}, {@code route_stops}, {@code route_stop_orders},
 * {@code dispatch_rules}, {@code plan_explanations} 매핑 (DESIGN.md §5.3).
 *
 * <p>자기 서비스 DB({@code dawnline_dispatch})만 접근한다(CLAUDE.md 불변규칙 3).
 */
@NullMarked
package com.dawnline.dispatch.adapter.out.persistence;

import org.jspecify.annotations.NullMarked;
