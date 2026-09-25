package com.dawnline.messaging.outbox;

import java.util.Optional;

/**
 * 현재 스레드의 W3C {@code traceparent} 를 제공한다 (DESIGN.md §9.2).
 *
 * <p>이 포트는 트레이싱 타입을 모른다 — 도구처럼 트레이싱 스택이 없는 소비자도 {@code libs/messaging} 을 쓴다. 구현
 * ({@code com.dawnline.messaging.tracing.TracerTraceparentSupplier})은 트레이싱 클래스가 있을 때만 자동 설정이 고른다.
 * 행에 실린 값은 릴레이가 Kafka 헤더와 봉투의 {@code traceId} 로 전파하고, 발행은 그 값을 부모로 삼는다
 * ({@code KafkaRecordPublisher}).
 *
 * <p><strong>7-2 까지 구현이 없었다.</strong> 이 문서는 「{@code libs/observability} 가 등록한다」고 했지만 그
 * 등록은 한 번도 없었고 기본값 {@link #NONE} 이 기능을 조용히 껐다(DESIGN.md §13 — 꺼 둔 검증의 운영 코드판).
 * {@code OutboxTraceparentIT} 가 그 상태를 빨강으로 본다.
 */
@FunctionalInterface
public interface TraceparentSupplier {

    /** 트레이스 컨텍스트가 없을 때 쓰는 기본 구현. */
    TraceparentSupplier NONE = Optional::empty;

    /**
     * @return 현재 {@code traceparent} 헤더 값 ({@code 00-<trace-id>-<span-id>-<flags>}).
     *         활성 트레이스가 없으면 비어 있다.
     */
    Optional<String> currentTraceparent();
}
