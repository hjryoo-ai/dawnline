/**
 * 메시징 아웃바운드 어댑터 — {@code outbox_events} 기록 (DESIGN.md §4.4).
 *
 * <p>계획 결과 쓰기는 plan 단위 트랜잭션이라 부분 결과가 발행되지 않는다(§5.3).
 */
@NullMarked
package com.dawnline.dispatch.adapter.out.messaging;

import org.jspecify.annotations.NullMarked;
