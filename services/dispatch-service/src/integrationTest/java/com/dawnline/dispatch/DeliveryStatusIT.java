package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.adapter.out.redis.RedisRouteProgressCache;
import com.dawnline.dispatch.application.port.in.PlanView;
import com.dawnline.dispatch.application.port.in.RouteView;
import com.dawnline.dispatch.application.port.in.RunPlanCommand;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.application.port.out.PlanQueries;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.messaging.contract.EventContracts;
import com.redis.testcontainers.RedisContainer;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 브로커의 {@code delivery.status} → {@code route_stops.status} → {@code route:{id}:progress}
 * (Phase 5-5, ADR-047).
 *
 * <p>단위 테스트가 보는 것은 <em>판단</em>이다. 여기서 보는 것은 그 판단이 실물 브로커와 실물
 * PostgreSQL·Redis 를 지났을 때의 결과다 — 봉투 역직렬화, 멱등 게이트, 그리고 캐시에 실제로
 * 무엇이 적히는가.
 *
 * <p>Redis 가 <strong>죽었을 때</strong>의 같은 경로는 {@code RouteProgressFallbackIT} 가 본다.
 */
@SpringBootTest(classes = DispatchApplication.class)
@Import(PlanningClock.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("DeliveryStatusIT — 브로커에서 route_stops 까지")
class DeliveryStatusIT extends DispatchIntegrationTestBase {

    /** deploy/compose/.env.example 의 {@code REDIS_IMAGE} 와 같은 태그. */
    private static final String REDIS_IMAGE = "redis:8.8.2";

    private static final RedisContainer REDIS = new RedisContainer(REDIS_IMAGE);

    /** 시드의 첫 캠프 (서울 북부). */
    private static final UUID CAMP_ID = UUID.fromString("01a06edd-6c00-7000-8001-000000000001");
    private static final GeoPoint CAMP = GeoPoint.of(37.640000, 127.030000);
    private static final String TOPIC = "dawnline.delivery.status.v1";

    private static KafkaProducer<String, String> producer;

    static {
        REDIS.start();
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
    private StringRedisTemplate redis;

    /**
     * 살아 있는 Redis 를 가리킨다.
     *
     * <p>{@code host}/{@code port} 가 아니라 {@code url} 로 적는다 — 다른
     * {@code @DynamicPropertySource} 와의 적용 순서가 보장되지 않기 때문이다
     * ({@code GeoFallbackIT} 가 그것으로 한 번 데였다).
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void liveRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    /**
     * <strong>릴레이를 끈다 — 이 클래스는 발행을 보지 않는다.</strong>
     *
     * <p>자기 {@code @DynamicPropertySource} 를 가지므로 컨텍스트가 하나 더 생기고, outbox
     * 리더십은 같은 DB 의 advisory lock <em>하나</em>다(ADR-027 후속 정정). 켜 둔 채로 두면 이
     * 클래스가 리더를 가져가 실제로 발행을 보는 IT 들이 조용히 팔로워가 된다 — 순서에 달린
     * 실패이고, 순서는 테스트가 말하는 것이 아니므로 근거가 될 수 없다.
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
            entityManager.createNativeQuery("DELETE FROM plan_explanations").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_stop_orders").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_stops").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM routes").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_plans").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM dispatch_candidates").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM outbox_events").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM processed_events").executeUpdate();
        });
    }

    @Test
    void 완료_스캔이_route_stops_를_옮기고_진행을_채운다() {
        Planned planned = plannedRoute();
        RouteView.StopView first = planned.stops().getFirst();

        publish(planned.routeId(), first.seq(), first.orderIds(), "COMPLETED");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(statusOf(planned.routeId(), first.seq())).isEqualTo("COMPLETED"));

        Map<Object, Object> progress = redis.opsForHash()
                .entries(RedisRouteProgressCache.key(planned.routeId()));
        assertThat(progress).as("§7.2 의 세 칸이 채워져야 한다")
                .containsEntry(RedisRouteProgressCache.FIELD_COMPLETED, "1")
                .containsEntry(RedisRouteProgressCache.FIELD_FAILED, "0")
                .containsEntry(RedisRouteProgressCache.FIELD_NEXT_SEQ, Integer.toString(planned.stops().get(1).seq()));
        assertThat(redis.getExpire(RedisRouteProgressCache.key(planned.routeId())))
                .as("TTL 이 붙어야 한다 — 없으면 키가 영영 남는다 (§7.2 2일)")
                .isPositive();
    }

    @Test
    void 같은_이벤트를_두_번_보내도_한_번만_반영된다() {
        // 불변규칙 2. 같은 eventId 라 processed_events 가 막는다 — 그리고 전이 규칙도 두 번째를
        // STALE 로 흡수한다. 둘 다 있어야 한다: 저쪽은 같은 이벤트를, 이쪽은 다른 이벤트를 막는다.
        Planned planned = plannedRoute();
        RouteView.StopView first = planned.stops().getFirst();
        String event = envelope(planned.routeId(), first.seq(), first.orderIds(), "ARRIVED");

        send(planned.routeId(), event);
        send(planned.routeId(), event);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(statusOf(planned.routeId(), first.seq())).isEqualTo("ARRIVED"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(processedCount()).isEqualTo(1L));
    }

    @Test
    void 취소된_stop_은_완료가_와도_그대로다() {
        // ADR-047 결정 3. CANCELLED 는 「계획에서 뺐다」는 뜻이라 여기에 COMPLETED 를 적으면
        // 이미 나간 개정과 저장된 계획이 어긋난다.
        Planned planned = plannedRoute();
        RouteView.StopView last = planned.stops().getLast();
        tx().executeWithoutResult(status -> entityManager.createNativeQuery(
                        "UPDATE route_stops SET status = 'CANCELLED' WHERE route_id = ? AND seq = ?")
                .setParameter(1, planned.routeId()).setParameter(2, last.seq()).executeUpdate());

        publish(planned.routeId(), last.seq(), last.orderIds(), "COMPLETED");
        // 앞 stop 에 다른 이벤트를 보내 «리스너가 여기까지 왔다» 를 확인한다 — 「아무 일도
        // 일어나지 않았다」를 기다림 없이 어설션하면 그저 느린 것과 구별되지 않는다.
        RouteView.StopView first = planned.stops().getFirst();
        publish(planned.routeId(), first.seq(), first.orderIds(), "COMPLETED");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(statusOf(planned.routeId(), first.seq())).isEqualTo("COMPLETED"));
        assertThat(statusOf(planned.routeId(), last.seq())).isEqualTo("CANCELLED");
    }

    @Test
    void 이_라우트에_없는_주문의_상태는_아무_행도_바꾸지_않는다() {
        // relocate 가 옮겼거나 개정이 지운 자리의 뒤늦은 스캔이다 (ADR-047 결정 1).
        Planned planned = plannedRoute();
        RouteView.StopView first = planned.stops().getFirst();

        publish(planned.routeId(), first.seq(), List.of(Ids.newId()), "COMPLETED");
        publish(planned.routeId(), first.seq(), first.orderIds(), "ARRIVED");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(statusOf(planned.routeId(), first.seq())).isEqualTo("ARRIVED"));
    }

    // --- 발행 ----------------------------------------------------------------

    private void publish(UUID routeId, int seq, List<UUID> orderIds, String status) {
        send(routeId, envelope(routeId, seq, orderIds, status));
    }

    private void send(UUID routeId, String event) {
        producer.send(new ProducerRecord<>(TOPIC, routeId.toString(), event));
        producer.flush();
    }

    /** 계약을 통과한 봉투만 보낸다 — 통과하지 못하는 이벤트로 소비자를 시험할 이유가 없다. */
    private static String envelope(UUID routeId, int seq, List<UUID> orderIds, String status) {
        String ids = orderIds.stream().map(id -> "\"" + id + "\"")
                .reduce((a, b) -> a + "," + b).orElseThrow();
        String json = """
                {"eventId":"%s","eventType":"delivery.status","schemaVersion":1,
                 "occurredAt":"%s","producer":"tracking-service","partitionKey":"%s",
                 "payload":{"routeId":"%s","stopSeq":%d,"orderIds":[%s],"status":"%s",
                            "occurredAt":"%s"}}
                """.formatted(Ids.newId(), PlanningClock.PLAN_AT, routeId, routeId, seq, ids,
                status, PlanningClock.PLAN_AT);
        EventContracts.load().validateRecord(json);
        return json;
    }

    // --- 조회 ----------------------------------------------------------------

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private String statusOf(UUID routeId, int seq) {
        return tx().execute(status -> (String) entityManager.createNativeQuery(
                        "SELECT status FROM route_stops WHERE route_id = ? AND seq = ?")
                .setParameter(1, routeId).setParameter(2, seq).getSingleResult());
    }

    private long processedCount() {
        return ((Number) tx().execute(status -> entityManager.createNativeQuery(
                "SELECT count(*) FROM processed_events").getSingleResult())).longValue();
    }

    // --- 픽스처 --------------------------------------------------------------

    private record Planned(UUID routeId, List<RouteView.StopView> stops) {
    }

    private Planned plannedRoute() {
        UUID waveId = Ids.newId();
        seedCandidates(waveId, 8);
        runPlan.run(RunPlanCommand.of(waveId, CAMP_ID, CAMP, null));
        PlanView plan = tx().execute(status -> planQueries.findPlanByWave(waveId)).orElseThrow();
        UUID routeId = plan.routes().getFirst().routeId();
        RouteView route = tx().execute(status -> planQueries.findRoute(routeId)).orElseThrow();
        assertThat(route.stops()).as("이 테스트는 stop 둘 이상을 전제한다").hasSizeGreaterThan(1);
        return new Planned(routeId, route.stops());
    }

    /** 약속창의 기준을 {@link PlanningClock#PLAN_AT} 에서 잡는다 — 벽시계가 아니다. */
    private List<UUID> seedCandidates(UUID waveId, int count) {
        Instant now = PlanningClock.PLAN_AT.truncatedTo(ChronoUnit.MICROS);
        TimeWindow window = new TimeWindow(now.plus(Duration.ofHours(1)),
                now.plus(Duration.ofHours(5)));
        List<UUID> orderIds = new ArrayList<>(count);
        tx().executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                UUID orderId = Ids.newId();
                orderIds.add(orderId);
                candidates.insertIfAbsent(DispatchCandidate.load(orderId, waveId, CAMP_ID, null,
                        GeoPoint.of(CAMP.lat() + 0.004d * (i % 8 + 1),
                                CAMP.lng() + 0.005d * (i / 8 + 1)),
                        40_000, 80_000, false, false, window, 60, false, 0, now));
            }
        });
        return orderIds;
    }
}
