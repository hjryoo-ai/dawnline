/**
 * Kafka 인바운드 어댑터 — {@code order.placed}, {@code order.cancelled} 리스너 (DESIGN.md §5.2).
 *
 * <p>{@code @KafkaListener} 는 이 패키지에만 존재한다(ArchUnit 규칙 4).
 * 순서 역전(취소가 접수보다 먼저 도착)은 취소 마커로 흡수한다(§4.5).
 */
@NullMarked
package com.dawnline.fulfillment.adapter.in.messaging;

import org.jspecify.annotations.NullMarked;
