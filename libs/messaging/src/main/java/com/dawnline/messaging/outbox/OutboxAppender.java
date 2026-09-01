package com.dawnline.messaging.outbox;

import com.dawnline.common.Ids;
import com.dawnline.messaging.EventHeaders;
import com.dawnline.messaging.json.EventJson;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * 유스케이스가 이벤트를 발행하는 <strong>유일한</strong> 진입점 (CLAUDE.md 불변규칙 1, DESIGN.md §4.4).
 *
 * <p>{@code KafkaTemplate} 을 유스케이스에서 직접 부르지 못하게 하는 것이 이 클래스의 존재 이유다.
 * 여기서 하는 일은 {@code outbox_events} 에 행 하나를 INSERT 하는 것뿐이고, 그 INSERT 는
 * 호출한 유스케이스의 트랜잭션에 그대로 참여한다. 도메인 변경이 롤백되면 이벤트도 사라진다.
 *
 * <p>봉투의 네 필드는 여기서 정한다.
 * <ul>
 *   <li>{@code eventId} — {@link Ids} 가 만든 UUIDv7 (불변규칙 10). outbox 행 id 이자 멱등 키.</li>
 *   <li>{@code occurredAt} — 주입된 {@link Clock} 의 현재 시각. 발행 시각이 아니라 <em>사건 시각</em>이다.
 *       릴레이가 5초 늦게 보내도 이 값은 변하지 않는다 (§4.2).</li>
 *   <li>{@code producer} — 서비스 이름.</li>
 *   <li>{@code traceId} — {@link TraceparentSupplier} 가 준 traceparent 에서 뽑는다.</li>
 * </ul>
 *
 * <p>Clock 과 Ids 를 주입받으므로 같은 시각·같은 seed 면 결과가 같다(불변규칙 12).
 */
public class OutboxAppender {

    private static final Pattern PRODUCER = Pattern.compile("^[a-z][a-z0-9-]*$");

    private final OutboxRepository repository;
    private final EventJson json;
    private final Ids ids;
    private final Clock clock;
    private final String producer;
    private final TraceparentSupplier traceparents;

    /**
     * @param repository   outbox 저장소
     * @param json         이벤트 전용 JSON 코덱
     * @param ids          UUIDv7 생성기 (불변규칙 10·12)
     * @param clock        사건 시각 출처 (불변규칙 12)
     * @param producer     발행 서비스 이름. 예: {@code order-service}
     * @param traceparents 현재 트레이스 컨텍스트 제공자
     */
    public OutboxAppender(OutboxRepository repository, EventJson json, Ids ids, Clock clock, String producer,
            TraceparentSupplier traceparents) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.json = Objects.requireNonNull(json, "json");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.producer = Objects.requireNonNull(producer, "producer");
        this.traceparents = Objects.requireNonNull(traceparents, "traceparents");
        if (!PRODUCER.matcher(producer).matches()) {
            // 기동 시점에 터뜨린다. 런타임에 봉투 검증으로 터지면 원인이 훨씬 멀어진다.
            throw new IllegalArgumentException(
                    "producer 는 소문자 kebab-case 여야 합니다(envelope.v1.schema.json): " + producer);
        }
    }

    /**
     * 이벤트를 outbox 에 기록한다. <strong>반드시 도메인 변경과 같은 트랜잭션 안에서</strong> 호출한다.
     *
     * @param message 발행 요청
     * @return 만들어진 {@code eventId}(= outbox 행 id). 로그 MDC 나 테스트 어설션에 쓴다.
     */
    public UUID append(OutboxMessage message) {
        Objects.requireNonNull(message, "message");

        JsonNode payloadNode = json.toTree(message.payload());
        if (!payloadNode.isObject()) {
            // envelope.v1.schema.json: payload 는 object 다. 배열·스칼라는 계약 위반이라 여기서 막는다.
            throw new IllegalArgumentException(
                    "payload 는 JSON 오브젝트여야 합니다: " + message.payload().getClass().getName());
        }

        UUID eventId = ids.newUuid();
        Instant occurredAt = clock.instant();

        OutboxEvent event = new OutboxEvent(
                eventId,
                message.aggregateType(),
                message.aggregateId(),
                message.eventType(),
                message.topic(),
                message.partitionKey(),
                json.write(headersFor(message)),
                json.write(payloadNode),
                occurredAt);

        repository.append(event);
        return eventId;
    }

    /**
     * §4.2 가 정한 세 헤더. {@code traceparent} 는 활성 트레이스가 있을 때만 넣는다.
     *
     * <p>봉투의 {@code producer}·{@code occurredAt} 은 헤더에 넣지 않는다. 헤더의 목적은
     * "페이로드를 열지 않고 라우팅·필터링" 이고, 그 둘은 라우팅에 쓰이지 않는다.
     */
    private Map<String, String> headersFor(OutboxMessage message) {
        Map<String, String> headers = new LinkedHashMap<>(4);
        headers.put(EventHeaders.EVENT_TYPE, message.eventType());
        headers.put(EventHeaders.SCHEMA_VERSION, Integer.toString(message.schemaVersion()));
        traceparents.currentTraceparent()
                .filter(EventHeaders::isValidTraceparent)
                .ifPresent(traceparent -> headers.put(EventHeaders.TRACEPARENT, traceparent));
        return headers;
    }

    /** 이 어펜더가 쓰는 발행자 이름. */
    public String producer() {
        return producer;
    }
}
