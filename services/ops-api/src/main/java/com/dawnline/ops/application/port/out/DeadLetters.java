package com.dawnline.ops.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * DLQ 를 읽고 원래 토픽에 다시 보낸다 (DESIGN.md §4.6 「DLQ 재처리」, ADR-053).
 *
 * <p>토픽은 언제나 <strong>원래 토픽</strong>의 이름으로 주고받는다 — DLQ 의 이름({@code <topic>.dlq})은 어댑터가 안다.
 *
 * <p>이 포트가 value 를 내놓는 곳은 {@link Raw} 하나뿐이고, 그것은 {@link #republish} 로 돌려주기 위해서만 있다.
 * 애플리케이션은 그 바이트를 열지 않는다 — 재처리는 원래 바이트 그대로여야 {@code eventId} 가 유지된다(결정 1).
 */
public interface DeadLetters {

    /**
     * 파티션마다 끝에서 {@code limit} 개까지 읽어 최근 것부터 {@code limit} 개.
     *
     * @param topic 원래 토픽
     * @param limit 최대 개수
     * @return DLQ 레코드. DLQ 토픽이 없으면 비어 있다
     */
    List<DeadLetter> peek(String topic, int limit);

    /**
     * 한 레코드를 읽는다.
     *
     * @param topic     원래 토픽
     * @param partition DLQ 의 파티션
     * @param offset    DLQ 의 오프셋
     * @return 레코드. 없으면(보존이 지났거나 오프셋이 끝 너머) 비어 있다
     * @throws RuntimeException 브로커에서 읽지 못했을 때 — 없다는 것도 확인하지 못했다
     */
    Optional<DeadLetter> read(String topic, int partition, long offset);

    /**
     * 원래 바이트를 원래 토픽·파티션에 다시 보내고, {@code targetGroup} 을 지목한다. 예외를 던지지 않는다.
     *
     * @param letter      {@link #read} 가 준 레코드 — 원래 토픽·파티션을 들고 있어야 한다
     * @param targetGroup 지목할 소비자 그룹
     * @return 브로커의 답
     */
    Delivery republish(DeadLetter letter, String targetGroup);

    /**
     * DLQ 레코드 하나 — 위치와 Spring 이 붙인 원래 위치, 그리고 value 에서 읽은 {@code eventId}.
     *
     * @param partition         DLQ 의 파티션
     * @param offset            DLQ 의 오프셋
     * @param timestamp         DLQ 레코드의 시각
     * @param originalTopic     {@code kafka_dlt-original-topic}
     * @param originalPartition {@code kafka_dlt-original-partition}
     * @param originalOffset    {@code kafka_dlt-original-offset}
     * @param originalGroup     {@code kafka_dlt-original-consumer-group} — 실패한 그룹. 리스너 실패일 때만 있다
     * @param eventType         헤더의 {@code eventType}
     * @param eventId           value 의 {@code eventId} — 읽지 못하면 비어 있다
     * @param exceptionClass    원인 예외의 클래스 이름 — 메시지는 싣지 않는다(§9.3)
     * @param raw               원래 바이트 — {@link #republish} 로만 돌려준다
     */
    record DeadLetter(int partition, long offset, Instant timestamp, @Nullable String originalTopic,
            @Nullable Integer originalPartition, @Nullable Long originalOffset, @Nullable String originalGroup,
            @Nullable String eventType, @Nullable UUID eventId, @Nullable String exceptionClass, Raw raw) {
        public DeadLetter {
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(raw, "raw");
        }
    }

    /**
     * 원래 바이트. 애플리케이션은 열지 않는다.
     *
     * @param key     키
     * @param value   value — 봉투, 그 안에 {@code eventId}
     * @param headers 헤더 전부(순서 그대로, {@code kafka_dlt-*} 포함 — 빼는 것은 어댑터가 보낼 때 한다)
     */
    record Raw(byte @Nullable [] key, byte @Nullable [] value, List<Header> headers) {
        public Raw {
            headers = List.copyOf(headers);
        }
    }

    /**
     * @param name  이름
     * @param value 값
     */
    record Header(String name, byte @Nullable [] value) {
        public Header {
            Objects.requireNonNull(name, "name");
        }
    }

    /** 브로커의 답 — §4.6 발행 측 표의 두 갈래와 ack. */
    sealed interface Delivery {

        /** 브로커가 받았다. */
        record Acked() implements Delivery {
        }

        /**
         * 브로커가 재시도 불가 오류로 거절했다 — 쓰이지 않은 것이 확실하다.
         *
         * @param detail 예외 클래스 이름
         */
        record Refused(String detail) implements Delivery {
        }

        /**
         * 보냈는지 모른다 — 타임아웃, 재시도 가능 오류의 소진, 분류할 수 없는 예외.
         *
         * @param detail 예외 클래스 이름
         */
        record Unknown(String detail) implements Delivery {
        }
    }
}
