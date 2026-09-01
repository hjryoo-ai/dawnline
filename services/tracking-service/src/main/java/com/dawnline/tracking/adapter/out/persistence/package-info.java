/**
 * JPA 아웃바운드 어댑터 — {@code shipments}, {@code shipment_events}(일 단위 파티션) 매핑 (DESIGN.md §5.4).
 *
 * <p>자기 서비스 DB({@code dawnline_tracking})만 접근한다(CLAUDE.md 불변규칙 3).
 */
@NullMarked
package com.dawnline.tracking.adapter.out.persistence;

import org.jspecify.annotations.NullMarked;
