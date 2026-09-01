package com.dawnline.messaging.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.Geohash;
import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.Topics;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * 계약 테스트 (CLAUDE.md 불변규칙 8, DESIGN.md §4.7, contracts/events/README.md §3).
 *
 * <p>스키마로 표현할 수 없는 불변식(필드 간 비교, 파생값 일치)은 여기서 코드로 검사한다.
 * 무엇을 검사해야 하는지는 contracts/events/README.md 3절의 표가 명세다.
 */
class EventContractsTest {

    private static final EventContracts CONTRACTS = EventContracts.load();

    /** §4.1 의 토픽별 파티션 키. 봉투의 partitionKey 가 이 필드와 같아야 순서 보장이 성립한다 (§4.5). */
    private static final Map<String, String> PARTITION_KEY_FIELD = Map.of(
            "order.placed", "orderId",
            "fulfillment.planned", "orderId",
            "wave.closed", "campId",
            "route.assigned", "routeId");

    static Stream<Path> examples() {
        return EventContracts.load().examples().stream();
    }

    @Test
    void 예시가_하나도_없으면_계약_테스트가_무의미하다() {
        assertThat(CONTRACTS.examples()).isNotEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    void 예시_봉투와_페이로드가_스키마를_통과한다(Path example) {
        CONTRACTS.validateRecord(CONTRACTS.readTree(example));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    void 예시_파일명과_봉투의_eventType_schemaVersion이_일치한다(Path example) {
        EventContracts.ExampleName expected = EventContracts.parseExampleName(example);
        JsonNode envelope = CONTRACTS.readTree(example);

        assertThat(envelope.get("eventType").asString()).isEqualTo(expected.eventType());
        assertThat(envelope.get("schemaVersion").intValue()).isEqualTo(expected.schemaVersion());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    void 예시_partitionKey가_페이로드의_키_필드와_같다(Path example) {
        JsonNode envelope = CONTRACTS.readTree(example);
        String eventType = envelope.get("eventType").asString();
        String keyField = PARTITION_KEY_FIELD.get(eventType);

        assertThat(keyField).as("§4.1 에 %s 의 파티션 키가 정의돼 있어야 한다", eventType).isNotNull();
        assertThat(envelope.get("partitionKey").asString())
                .isEqualTo(envelope.get("payload").get(keyField).asString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    void 예시_봉투로_역직렬화되고_불변식을_통과한다(Path example) {
        // 소비자 경로 회귀 검증 (§4.7): 예시가 실제 EventEnvelope 로 열려야 한다.
        String recordValue = CONTRACTS.readTree(example).toString();

        EventEnvelope<JsonNode> envelope = CONTRACTS.json().readEnvelope(recordValue);

        assertThat(envelope.eventId().version()).isEqualTo(7);
        assertThat(envelope.payload().isObject()).isTrue();
        assertThat(envelope.topic()).isEqualTo(
                Topics.forEvent(envelope.eventType(), envelope.schemaVersion()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    void 예시_주소가_있으면_geohash7이_좌표와_일치한다(Path example) {
        JsonNode address = CONTRACTS.readTree(example).get("payload").get("address");
        if (address == null) {
            return;
        }

        String expected = Geohash.encode(address.get("lat").doubleValue(), address.get("lng").doubleValue(),
                Geohash.STOP_PRECISION);
        assertThat(address.get("geohash7").asString()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    void 예시_배송약속창은_시작이_끝보다_이르다(Path example) {
        JsonNode window = CONTRACTS.readTree(example).get("payload").get("promisedWindow");
        if (window == null) {
            return;
        }

        OffsetDateTime start = OffsetDateTime.parse(window.get("start").asString());
        OffsetDateTime end = OffsetDateTime.parse(window.get("end").asString());
        assertThat(start).isBefore(end);
    }

    @Test
    void route_assigned_예시의_stop_불변식() {
        JsonNode payload = readExample("route.assigned.v1.example.json").get("payload");
        JsonNode stops = payload.get("stops");

        // summary.stopCount == stops 길이
        assertThat(payload.get("summary").get("stopCount").intValue()).isEqualTo(stops.size());

        List<Integer> sequences = new ArrayList<>();
        Set<String> assignedOrders = new LinkedHashSet<>();
        for (JsonNode stop : stops) {
            sequences.add(stop.get("seq").intValue());
            for (JsonNode orderId : stop.get("orderIds")) {
                // 한 주문이 두 stop 에 배정되면 안 된다.
                assertThat(assignedOrders.add(orderId.asString()))
                        .as("주문 %s 가 두 stop 에 배정됐다", orderId.asString()).isTrue();
            }
        }

        // seq 는 1..n 연속
        assertThat(sequences).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, stops.size())
                .boxed().toList());
    }

    @Test
    void 계약검증_format_위반을_실제로_거부한다() {
        // format 은 JSON Schema 2020-12 에서 기본이 주석이다. 켜지지 않았다면 이 테스트가 통과해 버린다.
        JsonNode broken = CONTRACTS.json().readTree("""
                {
                  "eventId": "01a04dad-80da-79a6-95d0-ba4369830bdf",
                  "eventType": "order.placed",
                  "schemaVersion": 1,
                  "occurredAt": "어제",
                  "producer": "order-service",
                  "partitionKey": "01a04dad-80da-7f6e-a63a-e91c103516b0",
                  "payload": {}
                }
                """);

        assertThatThrownBy(() -> CONTRACTS.validateEnvelope(broken))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("occurredAt");
    }

    @Test
    void 계약검증_UUIDv4_eventId를_거부한다() {
        // 4.3: eventId 만 버전 nibble 까지 강제한다(불변규칙 10).
        JsonNode broken = CONTRACTS.json().readTree("""
                {
                  "eventId": "d1b8b2a4-0f1e-4c3a-9b8e-3a1f0d2c4e5f",
                  "eventType": "order.placed",
                  "schemaVersion": 1,
                  "occurredAt": "2026-08-29T13:20:11.482Z",
                  "producer": "order-service",
                  "partitionKey": "01a04dad-80da-7f6e-a63a-e91c103516b0",
                  "payload": {}
                }
                """);

        assertThatThrownBy(() -> CONTRACTS.validateEnvelope(broken)).isInstanceOf(AssertionError.class);
    }

    @Test
    void 계약검증_모르는_필드는_허용한다() {
        // §4.7: 같은 major 안에서 필드 추가는 허용된다. 스키마를 닫지 않은 이유(README 4.1).
        JsonNode envelope = readExample("wave.closed.v1.example.json");
        ((tools.jackson.databind.node.ObjectNode) envelope).put("v1_1에서_추가된_필드", "무시되어야_한다");

        CONTRACTS.validateEnvelope(envelope);
    }

    @Test
    void 계약검증_스키마가_없으면_명확히_알려준다() {
        assertThatThrownBy(() -> CONTRACTS.validatePayload("order.dispatched", 1,
                CONTRACTS.json().readTree("{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("불변규칙 8");
    }

    private static JsonNode readExample(String fileName) {
        return CONTRACTS.readTree(CONTRACTS.contractsDirectory().resolve("examples").resolve(fileName));
    }
}
