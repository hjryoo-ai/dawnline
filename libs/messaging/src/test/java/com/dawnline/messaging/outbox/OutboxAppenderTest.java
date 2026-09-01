package com.dawnline.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.dawnline.common.Ids;
import com.dawnline.messaging.EventHeaders;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.messaging.support.InMemoryOutboxRepository;
import com.dawnline.messaging.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@link OutboxAppender} — 이벤트 발행의 유일한 진입점 (CLAUDE.md 불변규칙 1, DESIGN.md §4.4).
 */
class OutboxAppenderTest {

    /**
     * 페이로드 예시.
     *
     * @param orderId 주문 id
     */
    record OrderPlaced(String orderId) {
    }

    private static final Instant NOW = Instant.parse("2026-08-29T13:20:11.482Z");
    private static final String TRACEPARENT = "00-c4474d0fc15e10af509d95cbda4b78b0-00f067aa0ba902b7-01";
    private static final UUID ORDER_ID = UUID.fromString("01a04dad-80da-7f6e-a63a-e91c103516b0");

    private final MutableClock clock = MutableClock.at(NOW);
    private final EventJson json = EventJson.standard();
    private final InMemoryOutboxRepository repository = new InMemoryOutboxRepository(clock);

    @Test
    void append_행을_기록하고_eventId를_돌려준다() {
        OutboxAppender appender = appender(TraceparentSupplier.NONE);

        UUID eventId = appender.append(message());

        assertThat(repository.rows()).hasSize(1);
        OutboxEvent row = repository.rows().getFirst();
        assertThat(row.id()).isEqualTo(eventId);
        assertThat(row.aggregateType()).isEqualTo("Order");
        assertThat(row.aggregateId()).isEqualTo(ORDER_ID);
        assertThat(row.eventType()).isEqualTo("order.placed");
        assertThat(row.topic()).isEqualTo("dawnline.order.placed.v1");
        assertThat(row.partitionKey()).isEqualTo(ORDER_ID.toString());
        assertThat(row.isPublished()).isFalse();
    }

    @Test
    void append_eventId는_UUIDv7이다() {
        // 불변규칙 10. 그리고 UUIDv7 의 시간 성분은 주입된 Clock 을 따른다(불변규칙 12).
        UUID eventId = appender(TraceparentSupplier.NONE).append(message());

        assertThat(eventId.version()).isEqualTo(7);
        assertThat(Ids.timestampOf(eventId)).isEqualTo(NOW.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    void append_occurredAt은_발행시각이_아니라_사건시각이다() {
        OutboxAppender appender = appender(TraceparentSupplier.NONE);

        appender.append(message());
        clock.advance(Duration.ofMinutes(5));

        // 릴레이가 5분 뒤에 보내도 created_at 은 사건 시각 그대로다 (§4.2).
        assertThat(repository.rows().getFirst().createdAt()).isEqualTo(NOW);
    }

    @Test
    void append_헤더에_eventType과_schemaVersion을_중복기록한다() {
        appender(TraceparentSupplier.NONE).append(message());

        JsonNode headers = json.readTree(repository.rows().getFirst().headers());
        assertThat(headers.get(EventHeaders.EVENT_TYPE).asString()).isEqualTo("order.placed");
        assertThat(headers.get(EventHeaders.SCHEMA_VERSION).asString()).isEqualTo("1");
        assertThat(headers.has(EventHeaders.TRACEPARENT)).isFalse();
    }

    @Test
    void append_활성_트레이스가_있으면_traceparent를_싣는다() {
        appender(() -> Optional.of(TRACEPARENT)).append(message());

        JsonNode headers = json.readTree(repository.rows().getFirst().headers());
        assertThat(headers.get(EventHeaders.TRACEPARENT).asString()).isEqualTo(TRACEPARENT);
    }

    @Test
    void append_형식이_깨진_traceparent는_싣지_않는다() {
        appender(() -> Optional.of("garbage")).append(message());

        JsonNode headers = json.readTree(repository.rows().getFirst().headers());
        assertThat(headers.has(EventHeaders.TRACEPARENT)).isFalse();
    }

    @Test
    void append_payload를_JSON_오브젝트로_저장한다() {
        appender(TraceparentSupplier.NONE).append(message());

        JsonNode payload = json.readTree(repository.rows().getFirst().payload());
        assertThat(payload.isObject()).isTrue();
        assertThat(payload.get("orderId").asString()).isEqualTo(ORDER_ID.toString());
    }

    @Test
    void append_payload가_오브젝트가_아니면_예외() {
        // envelope.v1.schema.json 은 payload 를 object 로 정의한다. 리스트·스칼라는 계약 위반이다.
        OutboxAppender appender = appender(TraceparentSupplier.NONE);
        OutboxMessage listPayload = OutboxMessage.keyedByAggregate("Order", ORDER_ID, "order.placed", 1,
                java.util.List.of("a", "b"));

        assertThatIllegalArgumentException().isThrownBy(() -> appender.append(listPayload))
                .withMessageContaining("오브젝트");
    }

    @Test
    void 생성자_producer가_kebab_case가_아니면_기동시점에_예외() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OutboxAppender(repository, json, ids(), clock, "OrderService",
                        TraceparentSupplier.NONE))
                .withMessageContaining("kebab-case");
    }

    @Test
    void append_같은_밀리초에_두_번_불러도_eventId가_다르다() {
        OutboxAppender appender = appender(TraceparentSupplier.NONE);

        UUID first = appender.append(message());
        UUID second = appender.append(message());

        assertThat(first).isNotEqualTo(second);
    }

    private OutboxAppender appender(TraceparentSupplier traceparents) {
        return new OutboxAppender(repository, json, ids(), clock, "order-service", traceparents);
    }

    private Ids ids() {
        return new Ids(clock, new Random(42));
    }

    private static OutboxMessage message() {
        return OutboxMessage.keyedByAggregate("Order", ORDER_ID, "order.placed", 1, new OrderPlaced(ORDER_ID.toString()));
    }
}
