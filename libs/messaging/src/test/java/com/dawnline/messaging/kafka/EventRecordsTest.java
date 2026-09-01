package com.dawnline.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.Ids;
import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.messaging.support.MutableClock;
import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@link EventRecords} — 파싱 경계에서의 §4.6 분류.
 */
class EventRecordsTest {

    private static final Instant NOW = Instant.parse("2026-08-29T13:20:11.482Z");

    private final EventJson json = EventJson.standard();
    private final Ids ids = new Ids(MutableClock.at(NOW), new Random(42));

    @Test
    void parse_정상_레코드를_봉투로_연다() {
        UUID eventId = ids.newUuid();
        EventEnvelope<Map<String, String>> original = new EventEnvelope<>(eventId, "order.placed", 1, NOW,
                "order-service", eventId.toString(), null, Map.of("orderId", "o-1"));

        EventEnvelope<JsonNode> parsed = EventRecords.parse(json, record(json.write(original)));

        assertThat(parsed.eventId()).isEqualTo(eventId);
        assertThat(parsed.payload().get("orderId").asString()).isEqualTo("o-1");
    }

    @Test
    void parse_JSON이_깨졌으면_즉시_DLQ_대상_예외() {
        assertThatThrownBy(() -> EventRecords.parse(json, record("{ 이건 JSON 이 아니다")))
                .isInstanceOf(NonRetryableEventException.class)
                .hasMessageContaining("dawnline.order.placed.v1");
    }

    @Test
    void parse_봉투_불변식을_어기면_즉시_DLQ_대상_예외() {
        // eventId 가 UUIDv4 라 봉투 생성자가 거부한다 → 스키마 불일치이므로 §4.6 두 번째 줄.
        String v4Envelope = """
                {
                  "eventId": "d1b8b2a4-0f1e-4c3a-9b8e-3a1f0d2c4e5f",
                  "eventType": "order.placed",
                  "schemaVersion": 1,
                  "occurredAt": "2026-08-29T13:20:11.482Z",
                  "producer": "order-service",
                  "partitionKey": "o-1",
                  "payload": {}
                }
                """;

        assertThatThrownBy(() -> EventRecords.parse(json, record(v4Envelope)))
                .isInstanceOf(NonRetryableEventException.class);
    }

    @Test
    void parse_value가_null이면_즉시_DLQ_대상_예외() {
        // 톰스톤 레코드. 우리 토픽에는 압축이 없으므로 정상 경로가 아니다.
        assertThatThrownBy(() -> EventRecords.parse(json, record(null)))
                .isInstanceOf(NonRetryableEventException.class)
                .hasMessageContaining("비어 있습니다");
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("dawnline.order.placed.v1", 3, 42L, "o-1", value);
    }
}
