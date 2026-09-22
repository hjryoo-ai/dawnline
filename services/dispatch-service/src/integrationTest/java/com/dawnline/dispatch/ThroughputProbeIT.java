package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.common.Ids;
import com.dawnline.messaging.contract.EventContracts;
import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * <strong>일회성 계측</strong> — {@code docs/benchmarks/phase5-delivery-status-throughput.md} 의
 * §6 을 채운다. 조건은 그 문서에 <em>측정 전에</em> 적혀 있다.
 *
 * <p>회귀 감시가 아니라 한 번의 진단이므로 <strong>측정 커밋에만 남기고 다음 커밋에서 지운다</strong>
 * (Phase 4 의 {@code phase4-plan-roundtrip-breakdown.md} 와 같은 규칙 — 커밋 해시가 재현
 * 경로다). 남기면 CI 에 수십 초짜리 IT 가 붙는데, 이 값은 매번 확인할 값이 아니다.
 *
 * <p>측정값은 표준 출력으로 낸다. 어설션은 <strong>선기록한 통과선</strong>만 건다 — 조사선은
 * 사람이 보고 판단한다(문서 §4).
 */
@SpringBootTest(classes = DispatchApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("ThroughputProbeIT — 선기록한 조건으로 두 경로를 잰다")
class ThroughputProbeIT extends DispatchIntegrationTestBase {

    private static final String PLANNED_TOPIC = "dawnline.fulfillment.planned.v1";
    private static final String STATUS_TOPIC = "dawnline.delivery.status.v1";
    private static final EventContracts CONTRACTS = EventContracts.load();

    /** 문서 §4 의 입력. */
    private static final int BASELINE_EVENTS = 5_000;
    private static final int ROUTES = 50;
    private static final int STOPS_PER_ROUTE = 100;
    private static final int REPEATS = 3;

    /** 문서 §4 의 통과선. */
    private static final double PASS_A = 600.0d;
    private static final double PASS_B = 673.0d;

    /** 시드의 첫 캠프·차량. */
    private static final UUID CAMP_ID = UUID.fromString("01a06edd-6c00-7000-8001-000000000001");
    private static final UUID VEHICLE_ID = UUID.fromString("01a06edd-6c00-7000-8004-000000000001");

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.8.2")).withExposedPorts(6379);

    private static KafkaProducer<String, String> producer;

    static {
        REDIS.start();
        createTopics(PLANNED_TOPIC, STATUS_TOPIC);
    }

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private StringRedisTemplate redis;

    /**
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void probeProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        // 릴레이를 끈다 — 재는 것은 소비 경로다. 켜 두면 폴링 스레드가 같은 DB 를 두드린다.
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    @BeforeAll
    static void connect() {
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.LINGER_MS_CONFIG, "5",
                ProducerConfig.BATCH_SIZE_CONFIG, "65536"));
    }

    @AfterAll
    static void disconnect() {
        producer.close();
    }

    @Test
    void 기준A_fulfillment_planned_소비_처리량() {
        List<Double> rates = new ArrayList<>(REPEATS);
        for (int run = 1; run <= REPEATS; run++) {
            clean();
            List<String> events = new ArrayList<>(BASELINE_EVENTS);
            for (int i = 0; i < BASELINE_EVENTS; i++) {
                events.add(planned(Ids.newId()));
            }

            long startedAt = System.nanoTime();
            events.forEach(event -> producer.send(new ProducerRecord<>(PLANNED_TOPIC, null, event)));
            producer.flush();
            long publishedAt = System.nanoTime();
            await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() ->
                            assertThat(count("dispatch_candidates")).isEqualTo(BASELINE_EVENTS));
            long doneAt = System.nanoTime();

            double seconds = (doneAt - startedAt) / 1_000_000_000.0d;
            rates.add(BASELINE_EVENTS / seconds);
            report("A", run, BASELINE_EVENTS, startedAt, publishedAt, doneAt);
        }
        summarise("A", rates, PASS_A);
    }

    @Test
    void 기준B_delivery_status_소비_처리량() {
        List<Double> rates = new ArrayList<>(REPEATS);
        int events = 2 * ROUTES * STOPS_PER_ROUTE;
        for (int run = 1; run <= REPEATS; run++) {
            clean();
            List<UUID> routeIds = seedRoutes();
            List<String> payloads = statusEvents(routeIds);

            long startedAt = System.nanoTime();
            payloads.forEach(event ->
                    producer.send(new ProducerRecord<>(STATUS_TOPIC, null, event)));
            producer.flush();
            long publishedAt = System.nanoTime();
            await().atMost(Duration.ofMinutes(5)).pollInterval(Duration.ofMillis(200))
                    .untilAsserted(() -> assertThat(completedStops())
                            .isEqualTo(ROUTES * STOPS_PER_ROUTE));
            long doneAt = System.nanoTime();

            double seconds = (doneAt - startedAt) / 1_000_000_000.0d;
            rates.add(events / seconds);
            report("B", run, events, startedAt, publishedAt, doneAt);
        }
        summarise("B", rates, PASS_B);
        System.out.printf("PROBE B progress-keys=%d%n",
                redis.keys("route:*:progress").size());
    }

    // --- 출력 ----------------------------------------------------------------

    private static void report(String label, int run, int events, long startedAt, long publishedAt,
            long doneAt) {
        System.out.printf("PROBE %s run=%d events=%d publishMs=%.0f totalMs=%.0f rate=%.1f%n",
                label, run, events, (publishedAt - startedAt) / 1_000_000.0d,
                (doneAt - startedAt) / 1_000_000.0d,
                events / ((doneAt - startedAt) / 1_000_000_000.0d));
    }

    private static void summarise(String label, List<Double> rates, double pass) {
        List<Double> sorted = rates.stream().sorted().toList();
        double min = sorted.getFirst();
        double max = sorted.getLast();
        double median = sorted.get(sorted.size() / 2);
        System.out.printf("PROBE %s SUMMARY min=%.1f median=%.1f max=%.1f spread=%.1f%%%n",
                label, min, median, max, 100.0d * (max - min) / median);
        assertThat(min).as("선기록한 통과선 %.0f 건/초 (문서 §4)", pass).isGreaterThan(pass);
    }

    // --- 픽스처 --------------------------------------------------------------

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private void clean() {
        tx().executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM plan_explanations").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_stop_orders").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_stops").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM routes").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_plans").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM dispatch_candidates").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM outbox_events").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM processed_events").executeUpdate();
        });
        redis.keys("route:*:progress").forEach(redis::delete);
    }

    /**
     * 라우트 {@value #ROUTES} 개 × stop {@value #STOPS_PER_ROUTE} 개를 직접 넣는다.
     *
     * <p>계획을 돌려 만들지 않는 이유: 재는 것은 <strong>소비 경로</strong>이고 계획은 그 앞의
     * 일이다. 계획으로 만들면 stop 수가 알고리즘에 달려 실행마다 달라지고, 그러면 같은 조건의
     * 반복이 아니게 된다.
     */
    private List<UUID> seedRoutes() {
        Instant now = Instant.parse("2026-09-06T01:00:00Z");
        List<UUID> routeIds = new ArrayList<>(ROUTES);
        tx().executeWithoutResult(status -> {
            UUID planId = Ids.newId();
            entityManager.createNativeQuery("""
                    INSERT INTO route_plans (id, wave_id, camp_id, status, version)
                    VALUES (?, ?, ?, 'PUBLISHED', 0)
                    """).setParameter(1, planId).setParameter(2, Ids.newId())
                    .setParameter(3, CAMP_ID).executeUpdate();

            for (int r = 0; r < ROUTES; r++) {
                UUID routeId = Ids.newId();
                routeIds.add(routeId);
                entityManager.createNativeQuery("""
                        INSERT INTO routes (id, plan_id, vehicle_id, seq_no, status, revision,
                                            stop_count, distance_m, duration_s, cost_krw, version)
                        VALUES (?, ?, ?, ?, 'DISPATCHED', 1, ?, 0, 0, 0, 0)
                        """).setParameter(1, routeId).setParameter(2, planId)
                        .setParameter(3, VEHICLE_ID).setParameter(4, r + 1)
                        .setParameter(5, STOPS_PER_ROUTE).executeUpdate();

                for (int s = 1; s <= STOPS_PER_ROUTE; s++) {
                    UUID stopId = Ids.newId();
                    entityManager.createNativeQuery("""
                            INSERT INTO route_stops (id, route_id, seq, lat, lng, planned_arrival,
                                                     planned_departure, service_s, status)
                            VALUES (?, ?, ?, 37.6, 127.0, ?, ?, 60, 'PLANNED')
                            """).setParameter(1, stopId).setParameter(2, routeId)
                            .setParameter(3, s)
                            .setParameter(4, now.plusSeconds(60L * s))
                            .setParameter(5, now.plusSeconds(60L * s + 60)).executeUpdate();
                    entityManager.createNativeQuery(
                                    "INSERT INTO route_stop_orders (stop_id, order_id) VALUES (?, ?)")
                            .setParameter(1, stopId).setParameter(2, orderIdOf(routeId, s))
                            .executeUpdate();
                }
            }
        });
        return routeIds;
    }

    /** 라우트·순번에서 결정적으로 나오는 주문 id — 픽스처와 이벤트가 같은 값을 써야 한다. */
    private static UUID orderIdOf(UUID routeId, int seq) {
        return UUID.nameUUIDFromBytes((routeId + "/" + seq).getBytes());
    }

    /**
     * stop 마다 {@code ARRIVED} → {@code COMPLETED} 두 건. 라우트 사이는 <strong>섞는다</strong> —
     * 실제 기사들은 동시에 돌고, 한 라우트씩 몰면 같은 행에 잠금이 집중된다.
     */
    private static List<String> statusEvents(List<UUID> routeIds) {
        List<String> events = new ArrayList<>(2 * routeIds.size() * STOPS_PER_ROUTE);
        for (int s = 1; s <= STOPS_PER_ROUTE; s++) {
            for (UUID routeId : routeIds) {
                events.add(status(routeId, s, "ARRIVED"));
                events.add(status(routeId, s, "COMPLETED"));
            }
        }
        return events;
    }

    private static String status(UUID routeId, int seq, String value) {
        var envelope = CONTRACTS.readTree(CONTRACTS.contractsDirectory()
                .resolve(Path.of("examples", "delivery.status.v1.example.json")));
        var payload = (tools.jackson.databind.node.ObjectNode) envelope.get("payload");
        payload.put("routeId", routeId.toString());
        payload.put("stopSeq", seq);
        payload.set("orderIds", payload.arrayNode().add(orderIdOf(routeId, seq).toString()));
        payload.put("status", value);
        var root = (tools.jackson.databind.node.ObjectNode) envelope;
        root.put("eventId", Ids.newId().toString());
        root.put("partitionKey", routeId.toString());
        return root.toString();
    }

    private static String planned(UUID orderId) {
        var envelope = CONTRACTS.readTree(CONTRACTS.contractsDirectory()
                .resolve(Path.of("examples", "fulfillment.planned.v1.example.json")));
        var payload = (tools.jackson.databind.node.ObjectNode) envelope.get("payload");
        payload.put("orderId", orderId.toString());
        var root = (tools.jackson.databind.node.ObjectNode) envelope;
        root.put("eventId", Ids.newId().toString());
        root.put("partitionKey", orderId.toString());
        return root.toString();
    }

    private long count(String table) {
        return ((Number) tx().execute(status -> entityManager
                .createNativeQuery("SELECT count(*) FROM " + table).getSingleResult())).longValue();
    }

    private long completedStops() {
        return ((Number) tx().execute(status -> entityManager.createNativeQuery(
                        "SELECT count(*) FROM route_stops WHERE status = 'COMPLETED'")
                .getSingleResult())).longValue();
    }
}
