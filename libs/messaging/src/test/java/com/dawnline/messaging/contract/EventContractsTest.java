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
import org.junit.jupiter.params.provider.ValueSource;
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
            "order.cancelled", "orderId",
            "order.dispatched", "orderId",
            "fulfillment.planned", "orderId",
            "wave.closed", "campId",
            "route.assigned", "routeId",
            "plan.completed", "waveId",
            "plan.failed", "waveId",
            "delivery.status", "routeId",
            "delivery.at-risk", "routeId");

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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"route.assigned.v1.example.json", "route.assigned.v1.revised.example.json"})
    void route_assigned_예시의_stop_불변식(String file) {
        JsonNode payload = readExample(file).get("payload");
        JsonNode stops = payload.get("stops");

        // summary.stopCount == stops 길이. 취소된 stop 도 배열에 남으므로 여기 포함된다 (§6.10).
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
    void route_assigned_취소된_stop_은_지워지지_않고_seq_도_그대로다() {
        // §6.10 / ADR-026 — 부재는 값이 아니다. stop 을 배열에서 지우면 소비자가 "취소" 와
        // "다른 라우트로 이동" 과 "발행 누락" 을 구별할 수 없고, seq 를 다시 매기면 기사가 보던
        // 순번이 바뀐다. 예시 둘을 나란히 두는 이유가 그 둘을 실제로 대조하기 위해서다.
        JsonNode base = readExample("route.assigned.v1.example.json").get("payload");
        JsonNode revised = readExample("route.assigned.v1.revised.example.json").get("payload");

        assertThat(revised.get("routeId").asString()).isEqualTo(base.get("routeId").asString());
        assertThat(revised.get("revision").intValue())
                .as("개정은 revision 이 오른다 (§6.8 4단계)").isGreaterThan(base.get("revision").intValue());

        assertThat(seqOf(revised)).as("취소는 방문 순서를 바꾸지 않는다").isEqualTo(seqOf(base));
        assertThat(revised.get("stops").size())
                .as("취소된 stop 을 지우지 않는다").isEqualTo(base.get("stops").size());

        List<String> cancelled = new ArrayList<>();
        for (JsonNode stop : revised.get("stops")) {
            if ("CANCELLED".equals(stop.path("status").asString(""))) {
                cancelled.add(stop.get("seq").asString());
            }
        }
        assertThat(cancelled).as("개정 예시는 취소된 stop 을 실제로 담아야 뜻이 있다").hasSize(1);
    }

    @Test
    void route_assigned_status_없는_예시도_그대로_통과한다() {
        // status 는 optional·기본 PLANNED 다 (ADR-026 결정 4). v1 을 낸 생산자가 없어
        // "부재 = PLANNED" 가 실제 사실과 일치하기 때문이고, 기존 예시가 그대로 통과하는 것이
        // 그 판단이 major 변경이 아니라는 증거다 (계약 README 체크리스트).
        JsonNode base = readExample("route.assigned.v1.example.json");

        CONTRACTS.validateRecord(base);
        for (JsonNode stop : base.get("payload").get("stops")) {
            assertThat(stop.has("status")).as("기준 예시는 status 를 담지 않는다").isFalse();
        }
    }

    @Test
    void route_assigned_취소된_주문은_stop_안에서도_이름으로_남는다() {
        // §6.10 / ADR-026 [후속 정정 — Phase 3-6]. StopMerger 가 같은 지점·같은 약속창의 주문을
        // 하나의 stop 으로 묶으므로, 세 주문이 실린 stop 에서 하나만 취소되는 일이 일어난다.
        // 그때 stop 은 여전히 방문해야 해서 status 는 PLANNED 이고, 어느 주문이 죽었는지는
        // stop 의 상태로 말할 수 없다 — tracking 의 shipments 는 order_id 가 PK 다 (§5.4).
        JsonNode revised = readExample("route.assigned.v1.revised.example.json").get("payload");

        boolean sawPartial = false;
        boolean sawFull = false;
        for (JsonNode stop : revised.get("stops")) {
            List<String> orderIds = new ArrayList<>();
            stop.get("orderIds").forEach(id -> orderIds.add(id.asString()));
            List<String> cancelled = new ArrayList<>();
            stop.path("cancelledOrderIds").forEach(id -> cancelled.add(id.asString()));

            // 부분집합이 아니면 소비자는 자기 stop 에 없는 주문의 취소를 듣게 된다.
            assertThat(orderIds).as("cancelledOrderIds 는 orderIds 의 부분집합이다")
                    .containsAll(cancelled);

            boolean stopCancelled = "CANCELLED".equals(stop.path("status").asString(""));
            if (stopCancelled) {
                // 전부 취소된 stop 은 orderIds 를 비우지 않는다 — minItems 1 이고, 무엇이
                // 취소됐는지가 그 배열에만 있다.
                assertThat(cancelled).as("전부 취소된 stop 은 두 배열이 같다")
                        .containsExactlyElementsOf(orderIds);
                sawFull = true;
            } else if (!cancelled.isEmpty()) {
                assertThat(cancelled).as("일부만 취소된 stop 은 여전히 방문한다")
                        .hasSizeLessThan(orderIds.size());
                sawPartial = true;
            }
        }
        assertThat(sawFull).as("개정 예시에 전부 취소된 stop 이 있어야 뜻이 있다").isTrue();
        assertThat(sawPartial).as("개정 예시에 일부만 취소된 stop 이 있어야 뜻이 있다").isTrue();
    }

    @Test
    void route_assigned_취소는_이후_stop_의_도착을_당긴다() {
        // 순서는 그대로 두고 시간만 당긴다 (§6.10, ADR-026 결정 1). 다시 풀지 않으므로 stop 의
        // 좌표도 서비스 시간도 그대로이고, 건너뛴 stop 의 몫만큼 뒤가 앞으로 온다.
        JsonNode base = readExample("route.assigned.v1.example.json").get("payload");
        JsonNode revised = readExample("route.assigned.v1.revised.example.json").get("payload");

        OffsetDateTime baseLast = OffsetDateTime.parse(
                base.get("stops").get(2).get("plannedArrival").asString());
        OffsetDateTime revisedLast = OffsetDateTime.parse(
                revised.get("stops").get(2).get("plannedArrival").asString());
        assertThat(revisedLast).isBefore(baseLast);
        assertThat(revised.get("summary").get("durationS").intValue())
                .isLessThan(base.get("summary").get("durationS").intValue());
    }

    private static List<Integer> seqOf(JsonNode payload) {
        List<Integer> sequences = new ArrayList<>();
        payload.get("stops").forEach(stop -> sequences.add(stop.get("seq").intValue()));
        return sequences;
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
        // delivery.at-risk 는 아직 스키마가 없다(발행자인 tracking-service 가 Phase 5).
        // order-service 가 소비하지 않으므로 소비자 주도로 먼저 정의할 이유도 없다 — 그 상태를 그대로 쓴다.
        assertThatThrownBy(() -> CONTRACTS.validatePayload("delivery.at-risk", 1,
                CONTRACTS.json().readTree("{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("불변규칙 8");
    }

    private static JsonNode readExample(String fileName) {
        return CONTRACTS.readTree(CONTRACTS.contractsDirectory().resolve("examples").resolve(fileName));
    }
}
