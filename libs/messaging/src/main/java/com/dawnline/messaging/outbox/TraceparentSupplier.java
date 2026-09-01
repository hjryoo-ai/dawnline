package com.dawnline.messaging.outbox;

import java.util.Optional;

/**
 * 현재 스레드의 W3C {@code traceparent} 를 제공한다 (DESIGN.md §9.2).
 *
 * <p>{@code libs/messaging} 은 트레이싱 구현(Micrometer Tracing / OpenTelemetry)에 의존하지 않는다.
 * 트레이스 컨텍스트를 아는 것은 {@code libs/observability} 의 책임이므로, 여기서는 <em>구멍</em>만 남기고
 * 기본값을 "컨텍스트 없음" 으로 둔다. 관측용 필드 때문에 메시징이 관측성 스택에 묶이면 안 된다.
 *
 * <p>{@code libs/observability} 가 이 인터페이스의 빈을 등록하면 outbox 행에 traceparent 가 실리고,
 * 릴레이가 Kafka 헤더와 봉투의 {@code traceId} 로 전파한다.
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
