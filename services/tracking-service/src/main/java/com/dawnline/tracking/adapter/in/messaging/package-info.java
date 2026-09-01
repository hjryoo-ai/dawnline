/**
 * Kafka 인바운드 어댑터 — {@code route.assigned} 리스너 (DESIGN.md §5.4).
 *
 * <p>{@code @KafkaListener} 는 이 패키지에만 존재한다(ArchUnit 규칙 4).
 * {@code routeId + revision} 비교로 재계획 결과만 반영한다(§8.5).
 */
@NullMarked
package com.dawnline.tracking.adapter.in.messaging;

import org.jspecify.annotations.NullMarked;
