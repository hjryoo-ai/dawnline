/**
 * 메시징 아웃바운드 어댑터 — {@code outbox_events} 기록 (DESIGN.md §4.4).
 *
 * <p>유스케이스에서 {@code KafkaTemplate} 을 직접 호출하지 않는다(CLAUDE.md 불변규칙 1).
 * 실제 발행은 {@code libs/messaging} 의 릴레이가 담당한다.
 */
@NullMarked
package com.dawnline.order.adapter.out.messaging;

import org.jspecify.annotations.NullMarked;
