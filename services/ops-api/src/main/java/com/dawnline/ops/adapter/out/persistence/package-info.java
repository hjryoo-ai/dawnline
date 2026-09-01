/**
 * JPA 아웃바운드 어댑터 — 읽기 모델 {@code rm_orders}, {@code rm_waves}, {@code rm_routes},
 * {@code rm_kpi_hourly}, {@code audit_logs} 매핑 (DESIGN.md §5.5).
 *
 * <p>자기 서비스 DB({@code dawnline_ops})만 접근한다. 코어 서비스 테이블 JOIN·FK 금지(불변규칙 3).
 */
@NullMarked
package com.dawnline.ops.adapter.out.persistence;

import org.jspecify.annotations.NullMarked;
