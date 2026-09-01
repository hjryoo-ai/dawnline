/**
 * Kafka 인바운드 어댑터 — {@code order.dispatched}, {@code delivery.status} 리스너 (DESIGN.md §3.2).
 *
 * <p>{@code @KafkaListener} 는 이 패키지에만 존재한다(ArchUnit 규칙 4). 리스너는
 * {@code processed_events(event_id, consumer)} 멱등 검사를 트랜잭션 안에서 먼저 하고
 * 유스케이스를 호출하기만 한다(CLAUDE.md 불변규칙 2).
 */
@NullMarked
package com.dawnline.order.adapter.in.messaging;

import org.jspecify.annotations.NullMarked;
