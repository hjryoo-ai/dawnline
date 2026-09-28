package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.port.in.PlanView;
import com.dawnline.dispatch.application.port.in.RunPlanCommand;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.application.port.out.PlanQueries;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.messaging.contract.EventContracts;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 브로커의 {@code delivery.route-departed} → {@code routes.departed_at} (ADR-072) — 재계획의 앵커가 될 출발 사실.
 *
 * <p>계약의 봉투로 보낸다({@link EventContracts} 로 검증한 것만) — 이쪽이 상상한 모양이 아니라 tracking 이 내는 모양이다.
 */
@SpringBootTest(classes = DispatchApplication.class)
@Import(PlanningClock.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("RouteDepartedIT — 브로커에서 routes.departed_at 까지")
class RouteDepartedIT extends DispatchIntegrationTestBase {

    /** 시드의 첫 캠프 (서울 북부). */
    private static final UUID CAMP_ID = UUID.fromString("01a06edd-6c00-7000-8001-000000000001");
    private static final GeoPoint CAMP = GeoPoint.of(37.640000, 127.030000);
    private static final String TOPIC = "dawnline.delivery.route-departed.v1";

    private static KafkaProducer<String, String> producer;

    static {
        createTopics(TOPIC);
    }

    @Autowired
    private RunPlanUseCase runPlan;

    @Autowired
    private PlanQueries planQueries;

    @Autowired
    private DispatchCandidateRepository candidates;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MeterRegistry meters;

    /**
     * 릴레이를 끈다 — 이 클래스는 발행을 보지 않는다({@code DeliveryStatusIT} 와 같은 이유).
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void relayOff(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    @BeforeAll
    static void openProducer() {
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()));
    }

    @AfterAll
    static void closeProducer() {
        producer.close();
    }

    @BeforeEach
    void clean() {
        tx().executeWithoutResult(status -> {
            for (String table : new String[] {"plan_explanations", "route_stop_orders", "route_stops", "routes",
                    "route_plans", "dispatch_candidates", "outbox_events"}) {
                entityManager.createNativeQuery("DELETE FROM " + table).executeUpdate();
            }
        });
    }

    @Test
    void 출발이_라우트에_적히고_두_번째_출발은_덮지_않는다() {
        UUID routeId = plannedRoute();
        Instant departedAt = PlanningClock.PLAN_AT.plus(Duration.ofMinutes(12)).truncatedTo(ChronoUnit.MILLIS);

        send(routeId, envelope(Ids.newId(), routeId, departedAt));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(departedAt(routeId)).isEqualTo(departedAt));

        // 다른 사건 id 의 늦은 출발 — 단말의 재전송이 아니라 다른 스캔이다. 처음 떠난 시각이 사실이다(COALESCE).
        UUID later = Ids.newId();
        send(routeId, envelope(later, routeId, departedAt.plus(Duration.ofMinutes(5))));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(processed(later)).isTrue());
        assertThat(departedAt(routeId)).isEqualTo(departedAt);
    }

    @Test
    void 없는_라우트의_출발은_철_지난_사건으로_센다() {
        double before = staleCount();
        UUID unknownRoute = Ids.newId();
        UUID eventId = Ids.newId();

        send(unknownRoute, envelope(eventId, unknownRoute, PlanningClock.PLAN_AT));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(processed(eventId)).isTrue());
        assertThat(staleCount() - before).isEqualTo(1.0d);
    }

    // ---------------------------------------------------------------- 도우미

    private static String envelope(UUID eventId, UUID routeId, Instant departedAt) {
        String json = """
                {"eventId":"%s","eventType":"delivery.route-departed","schemaVersion":1,
                 "occurredAt":"%s","producer":"tracking-service","partitionKey":"%s",
                 "payload":{"routeId":"%s","campId":"%s","revision":1,
                            "plannedDeparture":"%s","departedAt":"%s"}}
                """.formatted(eventId, departedAt, routeId, routeId, CAMP_ID, PlanningClock.PLAN_AT, departedAt);
        EventContracts.load().validateRecord(json);
        return json;
    }

    private void send(UUID routeId, String event) {
        producer.send(new ProducerRecord<>(TOPIC, routeId.toString(), event));
        producer.flush();
    }

    private UUID plannedRoute() {
        UUID waveId = Ids.newId();
        Instant now = PlanningClock.PLAN_AT.truncatedTo(ChronoUnit.MICROS);
        TimeWindow window = new TimeWindow(now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(5)));
        tx().executeWithoutResult(status -> {
            for (int i = 0; i < 4; i++) {
                candidates.insertIfAbsent(DispatchCandidate.load(Ids.newId(), waveId, CAMP_ID, null,
                        GeoPoint.of(CAMP.lat() + 0.004d * (i + 1), CAMP.lng() + 0.005d),
                        20_000, 40_000, false, false, window, 60, false, 0, now));
            }
        });
        runPlan.run(RunPlanCommand.of(waveId, CAMP_ID, CAMP, null));
        PlanView plan = tx().execute(status -> planQueries.findPlanByWave(waveId)).orElseThrow();
        return plan.routes().getFirst().routeId();
    }

    private @Nullable Instant departedAt(UUID routeId) {
        return tx().execute(status -> (Instant) entityManager.createNativeQuery(
                "SELECT departed_at FROM routes WHERE id = ?").setParameter(1, routeId).getSingleResult());
    }

    private boolean processed(UUID eventId) {
        return tx().execute(status -> ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM processed_events WHERE event_id = ?").setParameter(1, eventId)
                .getSingleResult()).longValue() > 0);
    }

    private double staleCount() {
        var counter = meters.find("dawnline.event.stale")
                .tag("consumer", "dispatch").tag("eventType", "delivery.route-departed").counter();
        return counter == null ? 0.0d : counter.count();
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }
}
