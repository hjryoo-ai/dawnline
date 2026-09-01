package com.dawnline.messaging.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * {@code outbox_events} 한 행 (DESIGN.md §5.1 DDL, §4.4).
 *
 * <p>도메인 상태 변경과 <strong>같은 트랜잭션</strong>에서 INSERT 된다(CLAUDE.md 불변규칙 1).
 * 유스케이스는 이 클래스를 직접 다루지 않고 {@link OutboxAppender} 를 쓴다.
 *
 * <p>{@code id} 가 그대로 봉투의 {@code eventId} 이고 {@code processed_events} 의 멱등 키다.
 * 릴레이가 재발행해도 eventId 가 같으므로 소비자 쪽 중복 제거가 성립한다(§4.4 at-least-once).
 *
 * <p>{@code headers}·{@code payload} 는 {@code jsonb} 다. Hibernate 7 에서 PostgreSQL 의
 * {@code jsonb} 는 {@link SqlTypes#JSON} 으로 매핑되고, 필드는 이미 직렬화된 JSON 문자열을 담는다.
 * 여기서 다시 객체로 바꾸지 않는 이유: 릴레이는 페이로드의 <em>내용</em>에 관심이 없고,
 * 저장 시점의 바이트가 그대로 발행되어야 계약이 흔들리지 않기 때문이다.
 *
 * <p>상태 전이는 {@link #markPublished(Instant)} 하나뿐이다(불변규칙 6). 세터는 두지 않는다.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 32)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 64)
    private String eventType;

    @Column(name = "topic", nullable = false, updatable = false, length = 96)
    private String topic;

    @Column(name = "partition_key", nullable = false, updatable = false, length = 64)
    private String partitionKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers", nullable = false, updatable = false)
    private String headers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private @Nullable Instant publishedAt;

    /** JPA 전용. */
    protected OutboxEvent() {
        // Hibernate 가 프록시·인스턴스화에 쓴다.
    }

    /**
     * 새 미발행 행을 만든다.
     *
     * @param id            UUIDv7. 봉투의 {@code eventId} 가 된다.
     * @param aggregateType 애그리거트 타입. 예: {@code Order}
     * @param aggregateId   애그리거트 id
     * @param eventType     예: {@code order.placed}
     * @param topic         발행 대상 토픽 (§4.1)
     * @param partitionKey  파티션 키 (§4.5)
     * @param headers       Kafka 헤더 JSON 오브젝트 문자열
     * @param payload       페이로드 JSON 오브젝트 문자열
     * @param createdAt     도메인 사건 시각. 봉투의 {@code occurredAt} 이 된다.
     */
    public OutboxEvent(UUID id, String aggregateType, UUID aggregateId, String eventType, String topic,
            String partitionKey, String headers, String payload, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.aggregateType = Objects.requireNonNull(aggregateType, "aggregateType");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.partitionKey = Objects.requireNonNull(partitionKey, "partitionKey");
        this.headers = Objects.requireNonNull(headers, "headers");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * 발행 완료를 기록한다.
     *
     * @param publishedAt 발행 시각
     * @throws IllegalStateException 이미 발행된 행일 때. 릴레이가 같은 행을 두 번 커밋하려 한 것이므로
     *                               조용히 덮어쓰지 않고 드러낸다.
     */
    public void markPublished(Instant publishedAt) {
        Objects.requireNonNull(publishedAt, "publishedAt");
        if (this.publishedAt != null) {
            throw new IllegalStateException("이미 발행된 outbox 행입니다: id=" + id);
        }
        this.publishedAt = publishedAt;
    }

    public UUID id() {
        return id;
    }

    public String aggregateType() {
        return aggregateType;
    }

    public UUID aggregateId() {
        return aggregateId;
    }

    public String eventType() {
        return eventType;
    }

    public String topic() {
        return topic;
    }

    public String partitionKey() {
        return partitionKey;
    }

    /** Kafka 헤더 JSON 오브젝트 문자열. */
    public String headers() {
        return headers;
    }

    /** 페이로드 JSON 오브젝트 문자열. */
    public String payload() {
        return payload;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** 아직 발행되지 않았으면 비어 있다. */
    public Optional<Instant> publishedAt() {
        return Optional.ofNullable(publishedAt);
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    @Override
    public String toString() {
        // 페이로드는 개인정보를 담을 수 있다(§9.3). 로그에 나가지 않도록 절대 넣지 않는다.
        return "OutboxEvent[id=%s, eventType=%s, topic=%s, published=%s]"
                .formatted(id, eventType, topic, publishedAt != null);
    }
}
