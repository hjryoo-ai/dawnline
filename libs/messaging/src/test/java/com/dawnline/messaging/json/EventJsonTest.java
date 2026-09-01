package com.dawnline.messaging.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Ids;
import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.support.MutableClock;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 이벤트 JSON 직렬화 규칙 (DESIGN.md §4.2, §4.7).
 *
 * <p>Boot 4 의 기본 Jackson 은 3.x({@code tools.jackson.*})다. 여기서 확인하는 것은 그 위에서
 * 계약이 요구하는 세 가지가 실제로 성립하는가다 — 시간 표기, 미지 필드 무시, null 필드 생략.
 */
class EventJsonTest {

    /**
     * order.placed 페이로드의 축소판.
     *
     * @param orderId 주문 id
     * @param placedAt 접수 시각
     */
    record OrderPlaced(String orderId, Instant placedAt) {
    }

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-29T13:20:11.482Z");

    private final EventJson json = EventJson.standard();
    private final Ids ids = new Ids(MutableClock.at(OCCURRED_AT), new Random(7));

    @Test
    void write_Instant를_RFC3339_문자열로_쓴다() {
        // 계약의 "format": "date-time" 이 요구하는 형태. 숫자 타임스탬프면 스키마 검증이 깨진다.
        String result = json.write(new OrderPlaced("o-1", OCCURRED_AT));

        assertThat(result).contains("\"placedAt\":\"2026-08-29T13:20:11.482Z\"");
    }

    @Test
    void write_traceId가_null이면_필드를_아예_뺀다() {
        // "traceId": null 은 스키마(type: string)가 거부한다. 없는 필드는 허용된다.
        String result = json.write(envelope(null));

        assertThat(result).doesNotContain("traceId");
    }

    @Test
    void write_traceId가_있으면_넣는다() {
        String traceId = "c4474d0fc15e10af509d95cbda4b78b0";

        assertThat(json.write(envelope(traceId))).contains("\"traceId\":\"" + traceId + "\"");
    }

    @Test
    void readEnvelope_모르는_필드를_무시한다() {
        // §4.7: 같은 major 안에서는 필드 추가가 허용되고, 소비자는 모르는 필드를 무시해야 한다.
        UUID eventId = ids.newUuid();
        String withFutureField = """
                {
                  "eventId": "%s",
                  "eventType": "order.placed",
                  "schemaVersion": 1,
                  "occurredAt": "2026-08-29T13:20:11.482Z",
                  "producer": "order-service",
                  "partitionKey": "o-1",
                  "payload": { "orderId": "o-1", "placedAt": "2026-08-29T13:20:11.482Z" },
                  "이건_v1_1에서_추가된_필드": "구버전_소비자는_무시해야_한다"
                }
                """.formatted(eventId);

        EventEnvelope<OrderPlaced> envelope = json.readEnvelope(withFutureField, OrderPlaced.class);

        assertThat(envelope.eventId()).isEqualTo(eventId);
        assertThat(envelope.payload().orderId()).isEqualTo("o-1");
    }

    @Test
    void readEnvelope_payload를_트리로_남긴다() {
        String recordValue = json.write(envelope(null));

        EventEnvelope<JsonNode> envelope = json.readEnvelope(recordValue);

        assertThat(envelope.payload().get("orderId").asString()).isEqualTo("o-1");
    }

    @Test
    void convertPayload_트리를_record로_좁힌다() {
        EventEnvelope<JsonNode> envelope = json.readEnvelope(json.write(envelope(null)));

        OrderPlaced payload = json.convertPayload(envelope.payload(), OrderPlaced.class);

        assertThat(payload).isEqualTo(new OrderPlaced("o-1", OCCURRED_AT));
    }

    @Test
    void write_read_왕복하면_같은_봉투다() {
        EventEnvelope<OrderPlaced> original = envelope("c4474d0fc15e10af509d95cbda4b78b0");

        EventEnvelope<OrderPlaced> restored = json.readEnvelope(json.write(original), OrderPlaced.class);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void toTree_record를_오브젝트_노드로_바꾼다() {
        JsonNode tree = json.toTree(new OrderPlaced("o-1", OCCURRED_AT));

        assertThat(tree.isObject()).isTrue();
        assertThat(List.copyOf(tree.propertyNames())).containsExactlyInAnyOrder("orderId", "placedAt");
    }

    private EventEnvelope<OrderPlaced> envelope(String traceId) {
        return new EventEnvelope<>(ids.newUuid(), "order.placed", 1, OCCURRED_AT, "order-service", "o-1", traceId,
                new OrderPlaced("o-1", OCCURRED_AT));
    }
}
