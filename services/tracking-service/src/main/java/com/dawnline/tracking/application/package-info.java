/**
 * 애플리케이션 계층 — 유스케이스와 트랜잭션 경계 (DESIGN.md §3.4).
 *
 * <p>{@code @Transactional} 은 이 계층에만 둔다(ArchUnit 규칙 5). 도메인 상태 변경과
 * {@code outbox_events} 기록이 하나의 트랜잭션으로 묶이는 지점이다(CLAUDE.md 불변규칙 1).
 * {@code adapter} 패키지를 참조하지 않는다(ArchUnit 규칙 2).
 */
@NullMarked
package com.dawnline.tracking.application;

import org.jspecify.annotations.NullMarked;
