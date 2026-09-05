package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.port.in.RunPlanCommand;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.application.port.out.RoutePlanRepository;
import com.dawnline.dispatch.domain.CandidateStatus;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.dispatch.domain.PlanStatus;
import com.dawnline.messaging.contract.EventContracts;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 계획 실행이 <strong>실제 브로커까지</strong> 가는가 (Phase 3-5b 대조표의 세 열).
 *
 * <p>"outbox 에 들어갔다" 까지만 보면 릴레이와 봉투 조립이 검증되지 않는다 — Phase 1
 * {@code OrderPublishIT}, Phase 2 {@code FulfillmentPublishIT} 와 같은 형태다.
 *
 * <p>이 클래스만 릴레이를 켠다. 기반이 그것에 의견을 갖지 않는 이유는 기반 주석에 있다.
 */
@SpringBootTest(classes = DispatchApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("PlanExecutionIT — 계획 실행과 발행")
class PlanExecutionIT extends DispatchIntegrationTestBase {

    private static final String ROUTE_ASSIGNED = "dawnline.route.assigned.v1";
    private static final String ORDER_DISPATCHED = "dawnline.order.dispatched.v1";
    private static final String PLAN_COMPLETED = "dawnline.plan.completed.v1";
    private static final String PLAN_FAILED = "dawnline.plan.failed.v1";
    private static final EventContracts CONTRACTS = EventContracts.load();

    /** 시드의 첫 캠프 (서울 북부). 차량 20대가 여기 붙어 있다. */
    private static final UUID CAMP_ID =
            UUID.fromString("01a06edd-6c00-7000-8001-000000000001");
    private static final GeoPoint CAMP = GeoPoint.of(37.640000, 127.030000);

    private static KafkaConsumer<String, String> consumer;

    static {
        createTopics(ROUTE_ASSIGNED, ORDER_DISPATCHED, PLAN_COMPLETED, PLAN_FAILED);
    }

    @Autowired
    private RunPlanUseCase runPlan;

    @Autowired
    private DispatchCandidateRepository candidates;

    @Autowired
    private RoutePlanRepository plans;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 이 IT 만 릴레이를 켠다 — 발행이 브로커까지 가는 것이 검사 대상이다. */
    @DynamicPropertySource
    static void relay(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "true");
        registry.add("dawnline.messaging.outbox.poll-interval-ms", () -> "200");
    }

    @BeforeAll
    static void connect() {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "plan-execution-it-" + Ids.newId(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        consumer.subscribe(List.of(ROUTE_ASSIGNED, ORDER_DISPATCHED, PLAN_COMPLETED, PLAN_FAILED));
    }

    @AfterAll
    static void disconnect() {
        consumer.close();
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void clean() {
        tx().executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM plan_explanations").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_stop_orders").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_stops").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM routes").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_plans").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM dispatch_candidates").executeUpdate();
        });
    }

    @Test
    void 전제_릴레이가_돈다() {
        // 릴레이가 꺼져 있으면 아래 테스트들이 아무것도 발행되지 않은 채 브로커를 기다린다.
        assertThat(System.getProperty("spring.profiles.active", "")).doesNotContain("norelay");
    }

    @Test
    void 세_이벤트가_모두_브로커에_도착한다() {
        UUID waveId = Ids.newId();
        List<UUID> orderIds = seedCandidates(waveId, 6);

        RunPlanUseCase.Outcome outcome = runPlan.run(RunPlanCommand.of(waveId, CAMP_ID));

        assertThat(outcome).isEqualTo(RunPlanUseCase.Outcome.PUBLISHED);
        List<ConsumerRecord<String, String>> records =
                drainUntil(waveId, orderIds, ROUTE_ASSIGNED, ORDER_DISPATCHED, PLAN_COMPLETED);

        // 셋 다 봉투까지 계약을 지켜야 한다.
        records.forEach(record -> CONTRACTS.validateRecord(record.value()));

        assertThat(topicOf(records, PLAN_COMPLETED, waveId, orderIds)).as("웨이브당 하나").hasSize(1);
        assertThat(topicOf(records, ROUTE_ASSIGNED, waveId, orderIds)).as("라우트당 하나").isNotEmpty();
        assertThat(topicOf(records, ORDER_DISPATCHED, waveId, orderIds))
                .as("주문당 하나").hasSize(orderIds.size());
    }

    @Test
    void 파티션_키가_설계서와_같다() {
        // §4.1 — route.assigned 는 routeId, order.dispatched 는 orderId, plan.completed 는 waveId.
        UUID waveId = Ids.newId();
        List<UUID> orderIds = seedCandidates(waveId, 3);

        runPlan.run(RunPlanCommand.of(waveId, CAMP_ID));
        List<ConsumerRecord<String, String>> records =
                drainUntil(waveId, orderIds, ROUTE_ASSIGNED, ORDER_DISPATCHED, PLAN_COMPLETED);

        assertKey(records, ROUTE_ASSIGNED, "routeId", waveId, orderIds);
        assertKey(records, ORDER_DISPATCHED, "orderId", waveId, orderIds);
        assertKey(records, PLAN_COMPLETED, "waveId", waveId, orderIds);
    }

    @Test
    void 후보가_계획_결과대로_전이된다() {
        UUID waveId = Ids.newId();
        List<UUID> orderIds = seedCandidates(waveId, 4);

        runPlan.run(RunPlanCommand.of(waveId, CAMP_ID));

        List<CandidateStatus> statuses = tx().execute(status -> orderIds.stream()
                .map(id -> candidates.findById(id).orElseThrow().status()).toList());
        assertThat(statuses).allSatisfy(status ->
                assertThat(status).isIn(CandidateStatus.PLANNED, CandidateStatus.UNASSIGNED));
        List<DispatchCandidate> plannable =
                tx().execute(status -> candidates.findPlannableInWave(waveId));
        assertThat(plannable).isEmpty();
    }

    @Test
    void 같은_웨이브를_두_번_돌려도_계획은_하나다() {
        // route_plans.wave_id UNIQUE 가 만드는 멱등 (§5.3).
        UUID waveId = Ids.newId();
        seedCandidates(waveId, 3);

        assertThat(runPlan.run(RunPlanCommand.of(waveId, CAMP_ID)))
                .isEqualTo(RunPlanUseCase.Outcome.PUBLISHED);
        assertThat(runPlan.run(RunPlanCommand.of(waveId, CAMP_ID)))
                .isEqualTo(RunPlanUseCase.Outcome.ALREADY_PUBLISHED);
        assertThat(count("route_plans")).isEqualTo(1L);
    }

    @Test
    void 후보가_없으면_plan_failed_가_나간다() {
        UUID waveId = Ids.newId();

        assertThat(runPlan.run(RunPlanCommand.of(waveId, CAMP_ID)))
                .isEqualTo(RunPlanUseCase.Outcome.NO_CANDIDATES);

        List<ConsumerRecord<String, String>> records = drainUntil(waveId, List.of(), PLAN_FAILED);
        records.forEach(record -> CONTRACTS.validateRecord(record.value()));
        assertThat(topicOf(records, PLAN_FAILED, waveId, List.of())).hasSize(1);
        PlanStatus status = tx().execute(state -> plans.findByWaveId(waveId).orElseThrow().status());
        assertThat(status).isEqualTo(PlanStatus.FAILED);
    }

    @Test
    void 라우트와_stop_과_설명이_저장된다() {
        UUID waveId = Ids.newId();
        seedCandidates(waveId, 5);

        runPlan.run(RunPlanCommand.of(waveId, CAMP_ID));

        assertThat(count("routes")).isPositive();
        assertThat(count("route_stops")).isPositive();
        assertThat(count("route_stop_orders")).isEqualTo(5L);
        assertThat(count("plan_explanations"))
                .as("§6.3 — 운영자의 '왜' 에 답하는 유일한 기록").isEqualTo(5L);
    }

    private void assertKey(List<ConsumerRecord<String, String>> records, String topic,
            String field, UUID waveId, List<UUID> orders) {
        List<ConsumerRecord<String, String>> matched = topicOf(records, topic, waveId, orders);
        assertThat(matched).isNotEmpty();
        matched.forEach(record -> {
            JsonNode payload = CONTRACTS.json().readTree(record.value()).get("payload");
            assertThat(record.key()).as("%s 의 키는 %s 다", topic, field)
                    .isEqualTo(payload.get(field).asString());
        });
    }

    /**
     * 이 실행분만 남긴다.
     *
     * <p>컨슈머는 클래스 안에서 공유되고 오프셋이 이어지므로, 앞 테스트가 낸 레코드가 뒤
     * 테스트의 집계에 섞인다 — CI 가 "웨이브당 하나: expected 1 but was 2" 로 잡았고
     * 로컬은 실행 순서가 달라 통과했다. 토픽만 보고 세면 안 된다.
     *
     * @param records 지금까지 모은 레코드
     * @param topic   토픽
     * @param waveId  이번 실행의 웨이브
     * @param orders  이번 실행의 주문들 ({@code order.dispatched} 는 페이로드에 waveId 가 없다)
     */
    private static List<ConsumerRecord<String, String>> topicOf(
            List<ConsumerRecord<String, String>> records, String topic, UUID waveId,
            List<UUID> orders) {

        return records.stream()
                .filter(record -> record.topic().equals(topic))
                .filter(record -> belongsToRun(record, waveId, orders))
                .toList();
    }

    private static boolean belongsToRun(ConsumerRecord<String, String> record, UUID waveId,
            List<UUID> orders) {
        JsonNode payload = CONTRACTS.json().readTree(record.value()).get("payload");
        JsonNode wave = payload.get("waveId");
        if (wave != null) {
            return waveId.toString().equals(wave.asString());
        }
        JsonNode orderId = payload.get("orderId");
        return orderId != null
                && orders.stream().anyMatch(id -> id.toString().equals(orderId.asString()));
    }

    /** 이번 실행분이 모든 토픽에 도착할 때까지 모은다. */
    private List<ConsumerRecord<String, String>> drainUntil(UUID waveId, List<UUID> orders,
            String... topics) {

        List<ConsumerRecord<String, String>> seen = new ArrayList<>();
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    consumer.poll(Duration.ofMillis(200)).forEach(seen::add);
                    for (String topic : topics) {
                        assertThat(topicOf(seen, topic, waveId, orders))
                                .as("%s 도착 (waveId=%s)", topic, waveId).isNotEmpty();
                    }
                });
        return seen;
    }

    private long count(String table) {
        return tx().execute(status -> ((Number) entityManager
                .createNativeQuery("SELECT count(*) FROM " + table).getSingleResult()).longValue());
    }

    /** 캠프 주변에 후보를 흩는다. 차량 20대 · stop 상한 120 이라 넉넉히 들어간다. */
    private List<UUID> seedCandidates(UUID waveId, int count) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        TimeWindow window = new TimeWindow(now.plus(Duration.ofHours(1)),
                now.plus(Duration.ofHours(5)));
        List<UUID> orderIds = new ArrayList<>(count);
        tx().executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                UUID orderId = Ids.newId();
                orderIds.add(orderId);
                candidates.insertIfAbsent(DispatchCandidate.load(orderId, waveId, CAMP_ID, null,
                        GeoPoint.of(CAMP.lat() + 0.005d * (i + 1), CAMP.lng() + 0.004d * (i + 1)),
                        1_000, 2_000, false, false, window, 60, 0, now));
            }
        });
        return orderIds;
    }
}
