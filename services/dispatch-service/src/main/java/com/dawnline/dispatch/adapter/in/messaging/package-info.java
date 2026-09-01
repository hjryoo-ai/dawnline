/**
 * Kafka 인바운드 어댑터 — {@code fulfillment.planned}(후보 적재), {@code wave.closed}(계획 실행),
 * {@code delivery.at-risk}(부분 재계획) 리스너 (DESIGN.md §5.3, §6.8).
 *
 * <p>{@code @KafkaListener} 는 이 패키지에만 존재한다(ArchUnit 규칙 4).
 * {@code route_plans.wave_id} UNIQUE 로 중복 도착을 멱등 처리한다(§8.5).
 */
@NullMarked
package com.dawnline.dispatch.adapter.in.messaging;

import org.jspecify.annotations.NullMarked;
