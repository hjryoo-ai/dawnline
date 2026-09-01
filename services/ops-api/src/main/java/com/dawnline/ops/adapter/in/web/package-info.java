/**
 * REST 인바운드 어댑터 — KPI·웨이브·계획·라우트 조회와 운영자 커맨드
 * (웨이브 조기 마감, 재계획, stop 재배정, 주문 취소, DLQ replay) (DESIGN.md §5.5).
 *
 * <p>커맨드는 {@code OPS_OPERATOR} 이상 역할이 필요하고 모두 {@code audit_logs} 에 남는다.
 */
@NullMarked
package com.dawnline.ops.adapter.in.web;

import org.jspecify.annotations.NullMarked;
