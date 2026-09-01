package com.dawnline.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 모든 Kafka 레코드 value 의 최상위 구조 (DESIGN.md §4.2, contracts/events/envelope.v1.schema.json).
 *
 * <h2>payload 타입을 왜 제네릭으로 두었나</h2>
 *
 * 후보는 세 가지였다.
 *
 * <ol>
 *   <li>{@code JsonNode} 고정 — 발행 측이 매번 도메인 record 를 트리로 변환해야 한다.
 *       타입 안전성을 잃고, 발행 경로에 불필요한 중간 표현이 생긴다.</li>
 *   <li>{@code String} 고정 — 직렬화하면 {@code "payload":"{\"orderId\":...}"} 처럼
 *       <strong>이중 인코딩</strong>이 된다. 봉투 스키마는 payload 가 object 라고 정의하므로
 *       계약 위반이다. 이를 피하려면 커스텀 serializer 가 필요하고, 그러면 "단순함" 이라는
 *       유일한 장점이 사라진다.</li>
 *   <li><strong>제네릭 {@code T}</strong> — 채택. 발행 측은 도메인 record 를 그대로 담고
 *       (컴파일 타임 타입 안전), 릴레이·소비 측은 {@code EventEnvelope<JsonNode>} 로 읽어
 *       payload 를 열지 않고 통과시키거나 나중에 {@code convertValue} 로 좁힌다.
 *       Jackson 3 은 record 와 제네릭을 모두 기본 지원한다.</li>
 * </ol>
 *
 * <p>봉투는 payload 를 <em>열지 않고도</em> 라우팅·필터링이 가능해야 하므로(§4.2),
 * {@code eventType}·{@code schemaVersion}·{@code traceparent} 는 Kafka 헤더에도 중복 기록한다
 * ({@link EventHeaders}).
 *
 * <p>불변식은 생성자에서 강제한다. 계약(envelope.v1.schema.json)이 강제하는 것과 같은 규칙이며,
 * 위반은 §4.6 의 "스키마 불일치 → 즉시 DLQ" 경로로 흘러간다.
 *
 * @param <T> 페이로드 타입. 발행 측은 도메인 record, 소비·릴레이 측은 보통 {@code JsonNode}.
 * @param eventId       UUIDv7. {@code processed_events} 의 멱등 키다 (§4.4). Outbox 행 id 와 같다.
 * @param eventType     점으로 구분한 이벤트 타입. 예: {@code order.placed}
 * @param schemaVersion 페이로드 스키마 major 버전. 토픽 접미사 {@code v<major>} 와 같다.
 * @param occurredAt    도메인 사건 발생 시각. 발행 시각이 아니다 — outbox 행의 {@code created_at}.
 * @param producer      발행 서비스 이름. 예: {@code order-service}
 * @param partitionKey  Kafka 파티션 키 (§4.5). 없으면 순서 보장이 무너진다.
 * @param traceId       W3C trace-id (소문자 hex 32자). 관측용이므로 없을 수 있다.
 * @param payload       eventType 별 페이로드
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String producer,
        String partitionKey,
        @Nullable String traceId,
        T payload) {

    /** {@code producer} 형식. envelope.v1.schema.json 의 pattern 과 같다. */
    private static final Pattern PRODUCER = Pattern.compile("^[a-z][a-z0-9-]*$");

    /** W3C Trace Context 의 trace-id. 소문자 hex 32자. */
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");

    /** {@code outbox_events.partition_key} 가 VARCHAR(64) 이므로 상한도 64다. */
    private static final int MAX_PARTITION_KEY_LENGTH = 64;

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(partitionKey, "partitionKey");
        Objects.requireNonNull(payload, "payload");

        if (eventId.version() != 7) {
            // 불변규칙 10. v4 가 섞이면 processed_events 인덱스 지역성이 무너진다.
            throw new IllegalArgumentException("eventId 는 UUIDv7 이어야 합니다: version=" + eventId.version());
        }
        // 규칙은 Topics 한 곳에만 둔다. outbox 쓰기 경로(OutboxMessage)도 같은 검사를 쓰므로
        // "INSERT 는 되는데 릴레이가 터지는" 행이 애초에 만들어지지 않는다.
        Topics.requireValidEventType(eventType);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion 은 1 이상이어야 합니다: " + schemaVersion);
        }
        if (!PRODUCER.matcher(producer).matches()) {
            throw new IllegalArgumentException("producer 형식이 올바르지 않습니다: " + producer);
        }
        if (partitionKey.isEmpty() || partitionKey.length() > MAX_PARTITION_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "partitionKey 길이는 1..%d 여야 합니다: %d".formatted(MAX_PARTITION_KEY_LENGTH, partitionKey.length()));
        }
        // traceId 는 없어도 되지만(관측용, §4.2), 있으면 형식을 강제한다.
        if (traceId != null && !TRACE_ID.matcher(traceId).matches()) {
            throw new IllegalArgumentException("traceId 는 소문자 hex 32자여야 합니다: " + traceId);
        }
    }

    /** 이 봉투가 실릴 토픽 (§4.1 명명 규칙). */
    public String topic() {
        return Topics.forEvent(eventType, schemaVersion);
    }

    /** 트레이스 컨텍스트가 있을 때만 값이 있다. */
    public Optional<String> optionalTraceId() {
        return Optional.ofNullable(traceId);
    }

    /**
     * payload 만 다른 타입으로 바꾼 봉투를 만든다. 봉투 메타데이터는 그대로 보존된다.
     *
     * @param newPayload 새 페이로드
     * @param <R>        새 페이로드 타입
     */
    public <R> EventEnvelope<R> withPayload(R newPayload) {
        return new EventEnvelope<>(eventId, eventType, schemaVersion, occurredAt, producer, partitionKey, traceId,
                newPayload);
    }
}
