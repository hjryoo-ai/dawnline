package com.dawnline.messaging.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code processed_events} 한 행 — 소비 멱등의 근거 (DESIGN.md §5.1 DDL, §4.4, §8.5).
 *
 * <p>{@code (event_id, consumer)} 가 기본키다. 같은 이벤트를 여러 소비자가 각자 한 번씩 처리할 수 있고,
 * 한 소비자가 같은 이벤트를 두 번 처리할 수는 없다.
 *
 * <p>이 엔티티 자체는 거의 쓰이지 않는다. 쓰기 경로는 경합 조건 때문에 네이티브
 * {@code INSERT ... ON CONFLICT DO NOTHING} 이다({@link JpaProcessedEventRepository} 참고).
 * 그래도 엔티티를 두는 이유는 두 가지다 — {@code ddl-auto=validate} 가 이 테이블까지 검증하게 하려고,
 * 그리고 운영·테스트에서 조회할 타입이 필요해서다.
 */
@Entity
@Table(name = "processed_events")
@IdClass(ProcessedEventId.class)
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Id
    @Column(name = "consumer", nullable = false, updatable = false, length = 64)
    private String consumer;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    /** JPA 전용. */
    protected ProcessedEvent() {
        // Hibernate 가 프록시·인스턴스화에 쓴다.
    }

    /**
     * @param eventId     봉투의 {@code eventId} (UUIDv7)
     * @param consumer    소비자 이름. {@code processed_events.consumer} 는 VARCHAR(64).
     * @param processedAt 처리 시각
     */
    public ProcessedEvent(UUID eventId, String consumer, Instant processedAt) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.processedAt = Objects.requireNonNull(processedAt, "processedAt");
    }

    public UUID eventId() {
        return eventId;
    }

    public String consumer() {
        return consumer;
    }

    public Instant processedAt() {
        return processedAt;
    }

    @Override
    public String toString() {
        return "ProcessedEvent[eventId=%s, consumer=%s, processedAt=%s]".formatted(eventId, consumer, processedAt);
    }
}
