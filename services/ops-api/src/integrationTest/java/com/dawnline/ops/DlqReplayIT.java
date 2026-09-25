package com.dawnline.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.common.Ids;
import com.dawnline.messaging.EventHeaders;
import com.dawnline.messaging.MessagingMetrics;
import com.dawnline.messaging.Topics;
import com.dawnline.messaging.contract.EventContracts;
import com.dawnline.observability.DawnlineMetrics;
import com.dawnline.ops.adapter.in.messaging.ListenerTopics;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * DLQ 재처리 끝에서 끝까지 — 두 축 (DESIGN.md §4.6 「DLQ 재처리」, ADR-053).
 *
 * <ol>
 *   <li><strong>재처리한 이벤트가 원래 {@code eventId} 를 유지하는가</strong> — 원래 토픽에 다시 나간 레코드의 value 를
 *       바이트로 대조한다.</li>
 *   <li><strong>누가 눌렀는가</strong> — 레코드마다 감사 행 하나, {@code target_id} 가 {@code eventId}.</li>
 * </ol>
 *
 * <p>그리고 그 둘이 기대는 것 — 재처리가 <strong>원래 그룹에게만</strong> 의미 있는 사건이라는 것. 이 컨텍스트의
 * 소비자는 ops-api(그룹 {@code ops-api})이고, 다른 그룹(예: {@code fulfillment-service})을 지목한 재처리는 이 소비자가
 * 건너뛰어야 한다.
 *
 * <p>DLQ 레코드는 운영과 같은 {@link DeadLetterPublishingRecoverer} 로 만든다 — 헤더의 모양(원래 파티션의 4바이트 정수
 * 등)이 운영과 같다. 한 경우는 실제 리스너 실패로 DLQ 에 넣는다 — 원래 그룹 헤더가 실제로 붙는지, 그 값이 필터가 보는
 * 그룹과 같은지를 본다.
 *
 * <p>「건너뛰었다」는 부재라서 기다려서는 확인되지 않는다. 그래서 재처리 뒤 <strong>같은 파티션에 보초</strong> 하나를
 * 보내고, 보초가 처리된 뒤에 본다 — 그때 재처리 레코드는 이미 지나갔다.
 */
@SpringBootTest(classes = OpsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("DlqReplayIT — 원래 바이트를 원래 그룹에게만, 누가 눌렀는지와 함께")
class DlqReplayIT extends OpsIntegrationTestBase {

    /** deploy/compose/.env.example 의 {@code KAFKA_IMAGE} 와 같은 태그. */
    private static final String KAFKA_IMAGE = "apache/kafka:4.3.1";

    private static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE)
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    private static final String TOPIC = "dawnline.order.placed.v1";
    private static final String DLQ = Topics.dlqFor(TOPIC);
    private static final int PARTITION = 1;
    private static final String ACTOR_PREFIX = "it-dlq-replay-";
    private static final String SELF = "ops-api";
    private static final String OTHER = "fulfillment-service";

    static {
        KAFKA.start();
        // 원래 토픽과 DLQ 를 같은 파티션 수로 — compose 의 토픽 생성과 같다(§7.3).
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()))) {
            admin.createTopics(ListenerTopics.of().keySet().stream()
                            .flatMap(topic -> Stream.of(topic, Topics.dlqFor(topic)))
                            .map(topic -> new NewTopic(topic, 3, (short) 1)).toList())
                    .all().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("테스트 토픽을 만들지 못했습니다", e);
        }
    }

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
        registry.add("dawnline.ops.kpi.on-time-initial-delay-ms", () -> "3600000");
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private final EventContracts contracts = EventContracts.load();
    private final List<UUID> orders = new ArrayList<>();
    private final List<UUID> events = new ArrayList<>();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private MeterRegistry meters;

    @AfterEach
    void deleteOwnRows() {
        jdbc.update("DELETE FROM audit_logs WHERE actor LIKE ?", ACTOR_PREFIX + "%");
        for (UUID order : orders) {
            jdbc.update("DELETE FROM rm_orders WHERE order_id = ?", order);
        }
        for (UUID event : events) {
            jdbc.update("DELETE FROM processed_events WHERE event_id = ?", event);
        }
    }

    @Test
    void 리스너가_실패한_레코드는_원래_그룹을_달고_DLQ_에_들어가고_목록은_value_를_싣지_않는다() throws Exception {
        long offset = end(DLQ);

        kafka.send(new ProducerRecord<>(TOPIC, PARTITION, "broken", "{깨진 봉투 — 서울시 어딘가 1")).get();
        await().atMost(Duration.ofSeconds(30)).until(() -> end(DLQ) == offset + 1);

        HttpResponse<String> response = get("/api/v1/admin/dlq/" + TOPIC + "?limit=500", token("OPS_VIEWER", "viewer"));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode letter = recordAt(json.readTree(response.body()).get("records"), offset);
        assertThat(letter.get("originalGroup").asString())
                .as("Spring 이 적은 그룹 = 컨테이너가 필터에 알려 주는 그룹 — 같은 출처다").isEqualTo(SELF);
        assertThat(letter.get("originalPartition").asInt()).isEqualTo(PARTITION);
        assertThat(letter.get("eventId").isNull()).as("value 가 깨졌다 — 지어내지 않는다").isTrue();
        assertThat(letter.get("exceptionClass").asString()).isNotBlank();
        assertThat(response.body()).as("value 도 예외 메시지도 싣지 않는다(§9.3)").doesNotContain("서울시", "깨진");
    }

    @Test
    void 재처리는_원래_바이트를_원래_토픽과_파티션에_보내고_원래_그룹이_처리하며_누가_눌렀는지_남는다() throws Exception {
        Placed placed = placed();
        long dlqOffset = deadLetter(placed, SELF);
        long mainOffset = end(TOPIC);

        JsonNode result = replay(token("OPS_OPERATOR", "kim"), dlqOffset);

        assertThat(result.get("result").asString()).isEqualTo("SUCCEEDED");
        assertThat(result.get("eventId").asString()).isEqualTo(placed.eventId().toString());
        ConsumerRecord<byte[], byte[]> sent = readAt(TOPIC, mainOffset);
        assertThat(sent.value()).as("축 1 — value 가 한 바이트도 다르지 않다, 그래서 eventId 가 같다")
                .isEqualTo(placed.value().getBytes(StandardCharsets.UTF_8));
        assertThat(new String(sent.key(), StandardCharsets.UTF_8)).isEqualTo(placed.orderId().toString());
        assertThat(headerNames(sent)).contains(EventHeaders.EVENT_TYPE, EventHeaders.SCHEMA_VERSION, EventHeaders.TRACEPARENT)
                .noneMatch(name -> name.startsWith("kafka_dlt-"));
        assertThat(header(sent, EventHeaders.REPLAY_FOR)).isEqualTo(SELF);

        await().atMost(Duration.ofSeconds(30)).until(() -> processed(placed.eventId()) == 1);
        assertThat(orderStatus(placed.orderId())).isEqualTo("PLACED");

        Map<String, Object> row = jdbc.queryForMap("SELECT actor, action, target_type, target_id, request::text AS request,"
                + " result FROM audit_logs WHERE id = ?::uuid", result.get("auditId").asString());
        assertThat(row).as("축 2 — 누가, 무엇을")
                .containsEntry("actor", ACTOR_PREFIX + "kim")
                .containsEntry("action", "DLQ_REPLAY")
                .containsEntry("target_type", "EVENT")
                .containsEntry("target_id", placed.eventId())
                .containsEntry("result", "SUCCEEDED");
        assertThat((String) row.get("request")).contains("\"topic\": \"" + TOPIC + "\"", "\"partition\": 1",
                "\"offset\": " + dlqOffset, "\"targetGroup\": \"" + SELF + "\"");
    }

    @Test
    void 같은_레코드를_다시_눌러도_원래_그룹은_한_번만_처리한다() throws Exception {
        Placed placed = placed();
        long dlqOffset = deadLetter(placed, SELF);
        JsonNode first = replay(token("OPS_OPERATOR", "kim"), dlqOffset);
        await().atMost(Duration.ofSeconds(30)).until(() -> processed(placed.eventId()) == 1);
        double duplicatesBefore = outcome(MessagingMetrics.OUTCOME_DUP);

        JsonNode second = replay(token("OPS_OPERATOR", "kim"), dlqOffset);

        // 전제: 두 번째가 실제로 도착해 걸렸다 — 이것 없이 「한 번만」을 보면 아직 안 온 것과 구별되지 않는다.
        await().atMost(Duration.ofSeconds(30)).until(() -> outcome(MessagingMetrics.OUTCOME_DUP) == duplicatesBefore + 1);
        assertThat(processed(placed.eventId())).isEqualTo(1);
        assertThat(second.get("result").asString()).isEqualTo("SUCCEEDED");
        assertThat(second.get("auditId").asString()).as("다시 누르면 새 행이다 — 옛 행도 기록이다")
                .isNotEqualTo(first.get("auditId").asString());
        assertThat(jdbc.queryForList("SELECT result FROM audit_logs WHERE target_id = ? AND action = 'DLQ_REPLAY'",
                String.class, placed.eventId())).containsExactly("SUCCEEDED", "SUCCEEDED");
    }

    @Test
    void 다른_그룹을_지목한_재처리는_건너뛰고_기록하지_않으며_나중에_자기를_지목하면_처리한다() throws Exception {
        Placed placed = placed();
        double skippedBefore = outcome(MessagingMetrics.OUTCOME_REPLAY_NOT_TARGET);

        replay(token("OPS_OPERATOR", "kim"), deadLetter(placed, OTHER));
        sentinel();

        assertThat(outcome(MessagingMetrics.OUTCOME_REPLAY_NOT_TARGET)).isEqualTo(skippedBefore + 1);
        assertThat(processed(placed.eventId())).as("건너뛴 레코드는 processed_events 에 적지 않는다").isZero();
        assertThat(orderStatus(placed.orderId())).isNull();

        // 음성 표본 — 건너뛰며 (eventId, ops-api) 를 적었다면 여기서 dup 으로 막혀 행이 생기지 않는다.
        replay(token("OPS_OPERATOR", "kim"), deadLetter(placed, SELF));

        await().atMost(Duration.ofSeconds(30)).until(() -> processed(placed.eventId()) == 1);
        assertThat(orderStatus(placed.orderId())).isEqualTo("PLACED");
    }

    @Test
    void 보존이_지나_processed_events_가_지워진_뒤에도_다른_그룹을_지목한_재처리는_두_번_처리되지_않는다() throws Exception {
        // §4.4 의 처음 문장이 놓친 경우 — ops-api 는 이 이벤트를 성공 처리했고 fulfillment 만 실패했다. 14일이 지나
        // ops-api 의 processed_events 행이 정리됐다. 그 뒤 fulfillment 대상으로 재처리한다.
        Placed placed = placed();
        kafka.send(new ProducerRecord<>(TOPIC, PARTITION, placed.orderId().toString(), placed.value())).get();
        await().atMost(Duration.ofSeconds(30)).until(() -> processed(placed.eventId()) == 1);
        jdbc.update("DELETE FROM processed_events WHERE event_id = ? AND consumer = ?", placed.eventId(), SELF);

        replay(token("OPS_OPERATOR", "kim"), deadLetter(placed, OTHER));
        sentinel();

        assertThat(processed(placed.eventId())).as("ops-api 가 두 번째로 처리했다면 행이 다시 생긴다").isZero();
    }

    @Test
    void 대상_그룹을_모르거나_레코드가_없으면_보내지_않고_그래도_기록한다() throws Exception {
        Placed placed = placed();
        long noGroup = deadLetter(placed, null);
        long mainBefore = end(TOPIC);

        JsonNode results = post("/api/v1/admin/dlq/" + TOPIC + "/replay", token("OPS_OPERATOR", "kim"),
                "{\"records\":[{\"partition\":1,\"offset\":" + noGroup + "},{\"partition\":1,\"offset\":999999}]}")
                .json().get("results");

        assertThat(results.get(0).get("result").asString()).isEqualTo("REJECTED");
        assertThat(results.get(0).get("detail").asString()).isEqualTo("no-original-group");
        assertThat(results.get(1).get("result").asString()).isEqualTo("REJECTED");
        assertThat(results.get(1).get("detail").asString()).isEqualTo("not-found");
        assertThat(end(TOPIC)).as("아무것도 나가지 않았다").isEqualTo(mainBefore);
        assertThat(jdbc.queryForList("SELECT target_id FROM audit_logs WHERE actor LIKE ? ORDER BY id",
                UUID.class, ACTOR_PREFIX + "%")).containsExactly(placed.eventId(), null);
    }

    @Test
    void 계약에_없는_토픽은_404_이고_조회자는_재처리할_수_없고_어느_쪽도_기록하지_않는다() throws Exception {
        String body = "{\"records\":[{\"partition\":1,\"offset\":0}]}";

        assertThat(post("/api/v1/admin/dlq/dawnline.nope.v1/replay", token("OPS_OPERATOR", "kim"), body).status())
                .isEqualTo(404);
        assertThat(post("/api/v1/admin/dlq/" + DLQ + "/replay", token("OPS_OPERATOR", "kim"), body).status())
                .as("DLQ 의 이름이 아니라 원래 토픽을 받는다").isEqualTo(404);
        assertThat(get("/api/v1/admin/dlq/dawnline.nope.v1", token("OPS_VIEWER", "viewer")).statusCode()).isEqualTo(404);
        assertThat(post("/api/v1/admin/dlq/" + TOPIC + "/replay", token("OPS_VIEWER", "viewer"), body).status())
                .isEqualTo(403);
        assertThat(post("/api/v1/admin/dlq/" + TOPIC + "/replay", token("OPS_OPERATOR", "kim"), "{\"records\":[]}")
                .status()).isEqualTo(400);
        assertThat(jdbc.queryForList("SELECT id FROM audit_logs WHERE actor LIKE ?", ACTOR_PREFIX + "%")).isEmpty();
    }

    // --- 픽스처 --------------------------------------------------------------------------------

    private record Placed(UUID orderId, UUID eventId, String value) {
    }

    /** 계약 예시에서 새 주문 하나 — 검증을 거친 봉투. */
    private Placed placed() {
        UUID orderId = Ids.newId();
        UUID eventId = Ids.newId();
        ObjectNode envelope = (ObjectNode) contracts.readTree(
                contracts.contractsDirectory().resolve(Path.of("examples", "order.placed.v1.example.json")));
        envelope.put("eventId", eventId.toString());
        envelope.put("partitionKey", orderId.toString());
        ((ObjectNode) envelope.get("payload")).put("orderId", orderId.toString());
        contracts.validateRecord(envelope);
        orders.add(orderId);
        events.add(eventId);
        return new Placed(orderId, eventId, contracts.json().write(envelope));
    }

    /**
     * 운영과 같은 복구기로 DLQ 레코드 하나. {@code group} 이 있으면 리스너 실패({@link ListenerExecutionFailedException})
     * 라 Spring 이 원래 그룹 헤더를 붙이고, 없으면 붙이지 않는다.
     *
     * @return 그 레코드의 DLQ 오프셋
     */
    private long deadLetter(Placed placed, @Nullable String group) {
        long offset = end(DLQ);
        ConsumerRecord<String, String> original = new ConsumerRecord<>(TOPIC, PARTITION, 42L,
                placed.orderId().toString(), placed.value());
        original.headers().add(EventHeaders.EVENT_TYPE, EventHeaders.toBytes("order.placed"));
        original.headers().add(EventHeaders.SCHEMA_VERSION, EventHeaders.toBytes("1"));
        original.headers().add(EventHeaders.TRACEPARENT,
                EventHeaders.toBytes("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));
        Exception failure = group == null ? new IllegalStateException("리스너 밖의 실패")
                : new ListenerExecutionFailedException("리스너 실패", group, new IllegalStateException("하류 장애"));
        new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(Topics.dlqFor(record.topic()), record.partition()))
                .accept(original, failure);
        assertThat(end(DLQ)).as("DLQ 에 하나가 들어갔다").isEqualTo(offset + 1);
        return offset;
    }

    /** 같은 파티션에 보초 하나를 보내고 처리될 때까지 — 그 앞의 재처리 레코드는 이미 지나갔다. */
    private void sentinel() throws Exception {
        Placed sentinel = placed();
        kafka.send(new ProducerRecord<>(TOPIC, PARTITION, sentinel.orderId().toString(), sentinel.value())).get();
        await().atMost(Duration.ofSeconds(30)).until(() -> processed(sentinel.eventId()) == 1);
    }

    private JsonNode replay(String token, long dlqOffset) throws Exception {
        Response response = post("/api/v1/admin/dlq/" + TOPIC + "/replay", token,
                "{\"records\":[{\"partition\":" + PARTITION + ",\"offset\":" + dlqOffset + "}]}");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json().get("results").get(0);
    }

    // --- 도우미 --------------------------------------------------------------------------------

    private int processed(UUID eventId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ? AND consumer = ?",
                Integer.class, eventId, SELF);
        return count == null ? -1 : count;
    }

    private @Nullable String orderStatus(UUID orderId) {
        List<String> status = jdbc.queryForList("SELECT order_status FROM rm_orders WHERE order_id = ?", String.class,
                orderId);
        return status.isEmpty() ? null : status.getFirst();
    }

    private double outcome(String outcome) {
        Counter counter = meters.find(DawnlineMetrics.EVENT_PROCESSED.meterName())
                .tag(MessagingMetrics.TAG_CONSUMER, SELF)
                .tag(MessagingMetrics.TAG_EVENT_TYPE, "order.placed")
                .tag(MessagingMetrics.TAG_OUTCOME, outcome)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static JsonNode recordAt(JsonNode records, long offset) {
        for (JsonNode record : records) {
            if (record.get("partition").asInt() == PARTITION && record.get("offset").asLong() == offset) {
                return record;
            }
        }
        throw new AssertionError("목록에 " + PARTITION + "@" + offset + " 이 없다: " + records);
    }

    private static KafkaConsumer<byte[], byte[]> consumer() {
        return new KafkaConsumer<>(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false), new ByteArrayDeserializer(),
                new ByteArrayDeserializer());
    }

    private static long end(String topic) {
        TopicPartition partition = new TopicPartition(topic, PARTITION);
        try (KafkaConsumer<byte[], byte[]> consumer = consumer()) {
            return consumer.endOffsets(List.of(partition), Duration.ofSeconds(10)).get(partition);
        }
    }

    private static ConsumerRecord<byte[], byte[]> readAt(String topic, long offset) {
        TopicPartition partition = new TopicPartition(topic, PARTITION);
        try (KafkaConsumer<byte[], byte[]> consumer = consumer()) {
            consumer.assign(List.of(partition));
            consumer.seek(partition, offset);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<byte[], byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                    if (record.offset() == offset) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError(topic + "@" + offset + " 를 읽지 못했다");
    }

    private static List<String> headerNames(ConsumerRecord<?, ?> record) {
        return Arrays.stream(record.headers().toArray()).map(Header::key).toList();
    }

    private static @Nullable String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private record Response(int status, String body, JsonMapper mapper) {
        JsonNode json() {
            return mapper.readTree(body);
        }
    }

    private Response post(String path, String token, String body) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body(), json);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** {@code make token} 과 같은 다섯 클레임 ({@code OpsCommandIT} 와 같다). */
    private static String token(String role, String actor) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("dawnline-ops-token")
                .subject(ACTOR_PREFIX + actor)
                .claim("roles", List.of(role))
                .issueTime(Date.from(now.minusSeconds(60)))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
