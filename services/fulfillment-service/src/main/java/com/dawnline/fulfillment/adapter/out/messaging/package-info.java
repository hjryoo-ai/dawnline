/**
 * 메시징 아웃바운드 어댑터 — {@code outbox_events} 기록 (DESIGN.md §4.4).
 *
 * <p>유스케이스에서 {@code KafkaTemplate} 을 직접 호출하지 않는다(CLAUDE.md 불변규칙 1).
 */
@NullMarked
package com.dawnline.fulfillment.adapter.out.messaging;

import org.jspecify.annotations.NullMarked;
