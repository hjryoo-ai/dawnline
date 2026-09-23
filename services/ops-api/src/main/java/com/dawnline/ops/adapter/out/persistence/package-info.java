/**
 * 영속성 아웃바운드 어댑터 — 읽기 모델 {@code rm_orders}·{@code rm_waves}·{@code rm_routes}
 * (DESIGN.md §5.5).
 *
 * <p>JPA 가 아니라 JDBC 다. 프로젝션의 쓰기는 「이 칸들만」이라서(ADR-051 결정 2) 엔티티 전체를
 * 저장하는 모델과 맞지 않는다 — 엔티티를 저장하면 읽어 온 적 없는 칸까지 다시 적힌다.
 *
 * <p>자기 서비스 DB({@code dawnline_ops})만 접근한다. 코어 서비스 테이블 JOIN·FK 금지(불변규칙 3).
 */
@NullMarked
package com.dawnline.ops.adapter.out.persistence;

import org.jspecify.annotations.NullMarked;
