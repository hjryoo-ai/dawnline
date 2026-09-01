/**
 * Kafka 인바운드 어댑터 — 전 토픽 구독 후 읽기 모델 {@code rm_*} 프로젝션 갱신 (DESIGN.md §5.5).
 *
 * <p>{@code @KafkaListener} 는 이 패키지에만 존재한다(ArchUnit 규칙 4).
 * 같은 이벤트가 두 번 와도 프로젝션 결과가 같아야 한다(불변규칙 2).
 */
@NullMarked
package com.dawnline.ops.adapter.in.messaging;

import org.jspecify.annotations.NullMarked;
