package com.dawnline.messaging.outbox;

import com.dawnline.messaging.Topics;
import java.util.Objects;
import java.util.UUID;

/**
 * 유스케이스가 {@link OutboxAppender} 에 넘기는 발행 요청.
 *
 * <p>봉투({@code com.dawnline.messaging.EventEnvelope})와 다른 점: 여기에는
 * {@code eventId}·{@code occurredAt}·{@code producer}·{@code traceId} 가 없다.
 * 그 넷은 <strong>플랫폼이 정하는 값</strong>이라 유스케이스가 지정할 수 없어야 한다.
 * (eventId = UUIDv7 생성, occurredAt = 주입된 Clock, producer = 서비스 이름, traceId = 현재 트레이스)
 * 그래야 봉투가 항상 규칙대로 만들어지고, 테스트에서는 Clock·Ids 주입만으로 결정론이 성립한다.
 *
 * @param aggregateType 애그리거트 타입. {@code outbox_events.aggregate_type} 은 VARCHAR(32).
 * @param aggregateId   애그리거트 id
 * @param eventType     예: {@code order.placed}. VARCHAR(64).
 * @param schemaVersion 페이로드 스키마 major 버전
 * @param topic         발행 토픽. 보통 {@link #of} 가 §4.1 규칙으로 만들어 준다. VARCHAR(96).
 * @param partitionKey  파티션 키 (§4.5). VARCHAR(64).
 * @param payload       페이로드 record. JSON 오브젝트로 직렬화 가능해야 한다.
 */
public record OutboxMessage(
        String aggregateType,
        UUID aggregateId,
        String eventType,
        int schemaVersion,
        String topic,
        String partitionKey,
        Object payload) {

    private static final int MAX_AGGREGATE_TYPE = 32;
    private static final int MAX_EVENT_TYPE = 64;
    private static final int MAX_TOPIC = 96;
    private static final int MAX_PARTITION_KEY = 64;

    public OutboxMessage {
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(partitionKey, "partitionKey");
        Objects.requireNonNull(payload, "payload");

        // 컬럼 길이를 애플리케이션에서 먼저 막는다. DB 에서 잘리면 원인 파악이 훨씬 어렵다.
        checkLength("aggregateType", aggregateType, MAX_AGGREGATE_TYPE);
        checkLength("eventType", eventType, MAX_EVENT_TYPE);
        checkLength("topic", topic, MAX_TOPIC);
        checkLength("partitionKey", partitionKey, MAX_PARTITION_KEY);

        // 형식까지 여기서 막는다. 길이만 보면 "OrderPlaced" 같은 값이 INSERT 는 통과하고,
        // 릴레이가 EventEnvelope 를 만들 때 비로소 터진다. 그 행은 created_at 순서상 맨 앞이라
        // 뒤의 모든 이벤트를 영구히 막는 독약 행이 된다(§4.4 진행 보장 위반).
        // 쓰기 시점에 터뜨리면 도메인 트랜잭션과 함께 롤백되므로 그런 행 자체가 생기지 않는다.
        Topics.requireValidEventType(eventType);

        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion 은 1 이상이어야 합니다: " + schemaVersion);
        }
    }

    /**
     * 토픽 이름을 §4.1 규칙({@code dawnline.<eventType>.v<major>})으로 만들어 주는 팩토리.
     *
     * @param aggregateType 애그리거트 타입
     * @param aggregateId   애그리거트 id
     * @param eventType     이벤트 타입
     * @param schemaVersion 스키마 major 버전
     * @param partitionKey  파티션 키
     * @param payload       페이로드 record
     */
    public static OutboxMessage of(String aggregateType, UUID aggregateId, String eventType, int schemaVersion,
            String partitionKey, Object payload) {
        return new OutboxMessage(aggregateType, aggregateId, eventType, schemaVersion,
                Topics.forEvent(eventType, schemaVersion), partitionKey, payload);
    }

    /**
     * 파티션 키가 애그리거트 id 와 같은 흔한 경우 (§4.1 의 order/fulfillment/route 이벤트).
     *
     * @param aggregateType 애그리거트 타입
     * @param aggregateId   애그리거트 id 이자 파티션 키
     * @param eventType     이벤트 타입
     * @param schemaVersion 스키마 major 버전
     * @param payload       페이로드 record
     */
    public static OutboxMessage keyedByAggregate(String aggregateType, UUID aggregateId, String eventType,
            int schemaVersion, Object payload) {
        return of(aggregateType, aggregateId, eventType, schemaVersion, aggregateId.toString(), payload);
    }

    private static void checkLength(String field, String value, int max) {
        if (value.isEmpty() || value.length() > max) {
            throw new IllegalArgumentException(
                    "%s 길이는 1..%d 여야 합니다: %d".formatted(field, max, value.length()));
        }
    }
}
