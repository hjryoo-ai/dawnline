package com.dawnline.messaging.idempotency;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@link ProcessedEvent} 의 복합 기본키 {@code (event_id, consumer)} (DESIGN.md §5.1 DDL).
 *
 * <p>record 가 아니라 일반 클래스인 이유: JPA 의 {@code @IdClass} 는 <strong>public 무인자 생성자</strong>를
 * 요구하는데 record 는 그것을 가질 수 없다. {@code @EmbeddedId} + record 는 Hibernate 버전에 따라
 * 지원 여부가 달라져, 여기서는 사양이 확실한 쪽을 골랐다.
 */
public class ProcessedEventId implements Serializable {

    private UUID eventId;

    private String consumer;

    /** JPA 전용. */
    public ProcessedEventId() {
        // Hibernate 가 리플렉션으로 채운다.
    }

    /**
     * @param eventId  봉투의 {@code eventId} (UUIDv7)
     * @param consumer 소비자 이름
     */
    public ProcessedEventId(UUID eventId, String consumer) {
        this.eventId = eventId;
        this.consumer = consumer;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getConsumer() {
        return consumer;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProcessedEventId that)) {
            return false;
        }
        return Objects.equals(eventId, that.eventId) && Objects.equals(consumer, that.consumer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, consumer);
    }

    @Override
    public String toString() {
        return "ProcessedEventId[eventId=%s, consumer=%s]".formatted(eventId, consumer);
    }
}
