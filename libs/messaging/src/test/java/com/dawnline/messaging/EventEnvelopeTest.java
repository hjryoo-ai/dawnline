package com.dawnline.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.dawnline.common.Ids;
import com.dawnline.messaging.support.MutableClock;
import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 봉투 불변식 (DESIGN.md §4.2, contracts/events/envelope.v1.schema.json).
 *
 * <p>여기서 검증하는 규칙은 전부 계약 파일에도 같은 형태로 들어 있다. 두 곳에 둔 이유는
 * 계약은 <em>바깥</em>(다른 서비스가 받는 것)을 지키고, 생성자는 <em>안쪽</em>(잘못된 봉투가 애초에
 * 만들어지지 않게)을 지키기 때문이다.
 */
class EventEnvelopeTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-29T13:20:11.482Z");
    private static final String TRACE_ID = "c4474d0fc15e10af509d95cbda4b78b0";

    private final Ids ids = new Ids(MutableClock.at(OCCURRED_AT), new Random(42));

    @Test
    void 생성자_정상값_봉투가_만들어진다() {
        UUID eventId = ids.newUuid();

        EventEnvelope<Map<String, Object>> envelope = new EventEnvelope<>(eventId, "order.placed", 1, OCCURRED_AT,
                "order-service", "01a04dad-80da-7f6e-a63a-e91c103516b0", TRACE_ID, Map.of("orderId", "x"));

        assertThat(envelope.eventId()).isEqualTo(eventId);
        assertThat(envelope.topic()).isEqualTo("dawnline.order.placed.v1");
        assertThat(envelope.optionalTraceId()).contains(TRACE_ID);
    }

    @Test
    void 생성자_traceId가_없어도_만들어진다() {
        EventEnvelope<Map<String, Object>> envelope = envelope(null);

        assertThat(envelope.traceId()).isNull();
        assertThat(envelope.optionalTraceId()).isEmpty();
    }

    @Test
    void 생성자_UUIDv4_eventId_예외() {
        // 불변규칙 10: eventId 는 반드시 UUIDv7. v4 가 섞이면 인덱스 지역성과 시간순 정렬이 무너진다.
        UUID v4 = UUID.fromString("d1b8b2a4-0f1e-4c3a-9b8e-3a1f0d2c4e5f");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EventEnvelope<>(v4, "order.placed", 1, OCCURRED_AT, "order-service",
                        "key", null, Map.of()))
                .withMessageContaining("UUIDv7");
    }

    @Test
    void 생성자_잘못된_eventType_예외() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EventEnvelope<>(ids.newUuid(), "OrderPlaced", 1, OCCURRED_AT, "order-service",
                        "key", null, Map.of()))
                .withMessageContaining("eventType");
    }

    @Test
    void 생성자_점이_없는_eventType_예외() {
        // envelope.v1.schema.json 의 pattern 은 점을 최소 하나 요구한다.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EventEnvelope<>(ids.newUuid(), "placed", 1, OCCURRED_AT, "order-service",
                        "key", null, Map.of()));
    }

    @Test
    void 생성자_schemaVersion_0_예외() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EventEnvelope<>(ids.newUuid(), "order.placed", 0, OCCURRED_AT, "order-service",
                        "key", null, Map.of()))
                .withMessageContaining("schemaVersion");
    }

    @Test
    void 생성자_대문자_producer_예외() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EventEnvelope<>(ids.newUuid(), "order.placed", 1, OCCURRED_AT, "OrderService",
                        "key", null, Map.of()))
                .withMessageContaining("producer");
    }

    @Test
    void 생성자_빈_partitionKey_예외() {
        // partitionKey 가 없으면 §4.5 의 순서 보장이 무너진다. 관측이 아니라 정확성 문제라 required 다.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EventEnvelope<>(ids.newUuid(), "order.placed", 1, OCCURRED_AT, "order-service",
                        "", null, Map.of()))
                .withMessageContaining("partitionKey");
    }

    @Test
    void 생성자_64자를_넘는_partitionKey_예외() {
        // outbox_events.partition_key 가 VARCHAR(64) 이므로 상한도 64.
        String tooLong = "k".repeat(65);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EventEnvelope<>(ids.newUuid(), "order.placed", 1, OCCURRED_AT, "order-service",
                        tooLong, null, Map.of()));
    }

    @Test
    void 생성자_형식이_틀린_traceId_예외() {
        // 없는 것은 허용하고, 이상한 것은 거부한다 (contracts/events/README §4.2).
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EventEnvelope<>(ids.newUuid(), "order.placed", 1, OCCURRED_AT, "order-service",
                        "key", "NOT-A-TRACE-ID", Map.of()))
                .withMessageContaining("traceId");
    }

    @Test
    void 생성자_payload_null_예외() {
        assertThatNullPointerException()
                .isThrownBy(() -> new EventEnvelope<>(ids.newUuid(), "order.placed", 1, OCCURRED_AT, "order-service",
                        "key", null, null));
    }

    @Test
    void withPayload_메타데이터는_그대로_payload만_바뀐다() {
        EventEnvelope<Map<String, Object>> original = envelope(TRACE_ID);

        EventEnvelope<String> narrowed = original.withPayload("바뀐 페이로드");

        assertThat(narrowed.eventId()).isEqualTo(original.eventId());
        assertThat(narrowed.occurredAt()).isEqualTo(original.occurredAt());
        assertThat(narrowed.traceId()).isEqualTo(TRACE_ID);
        assertThat(narrowed.payload()).isEqualTo("바뀐 페이로드");
    }

    private EventEnvelope<Map<String, Object>> envelope(String traceId) {
        return new EventEnvelope<>(ids.newUuid(), "order.placed", 1, OCCURRED_AT, "order-service",
                "01a04dad-80da-7f6e-a63a-e91c103516b0", traceId, Map.of("orderId", "x"));
    }
}
