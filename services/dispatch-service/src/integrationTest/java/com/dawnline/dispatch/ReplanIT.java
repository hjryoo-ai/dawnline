package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.DispatchMetrics;
import com.dawnline.dispatch.application.port.in.PlanView;
import com.dawnline.dispatch.application.port.in.ReplanRouteUseCase.Outcome;
import com.dawnline.dispatch.application.port.in.RouteView;
import com.dawnline.dispatch.application.port.in.RunPlanCommand;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.application.port.out.PlanQueries;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.messaging.contract.EventContracts;
import com.dawnline.observability.DawnlineMetrics;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 브로커의 {@code delivery.at-risk} → §6.8 부분 재계획 (Phase 5-3, ADR-046 · ADR-048).
 *
 * <p>탐색의 규칙은 {@code RelocateSearchTest} 가, 그 바깥의 판단은
 * {@code ReplanRouteServiceTest} 가 본다. 여기서 보는 것은 <strong>실물 브로커와 실물
 * PostgreSQL 을 지났을 때</strong>다 — 계약을 통과한 봉투가 명령이 되는가, 쿨다운이 DB 에서
 * 실제로 두 번째를 막는가, 그리고 결과가 어느 라벨로 세어지는가.
 */
@SpringBootTest(classes = DispatchApplication.class)
@Import(PlanningClock.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("ReplanIT — at-risk 에서 revision 까지")
class ReplanIT extends DispatchIntegrationTestBase {

    /** 시드의 첫 캠프 (서울 북부). */
    private static final UUID CAMP_ID = UUID.fromString("01a06edd-6c00-7000-8001-000000000001");
    private static final GeoPoint CAMP = GeoPoint.of(37.640000, 127.030000);
    private static final String AT_RISK_TOPIC = "dawnline.delivery.at-risk.v1";
    private static final String STATUS_TOPIC = "dawnline.delivery.status.v1";

    /** 기사가 첫 지점에 이만큼 늦게 닿았다 — 재계획이 볼 편차다. */
    private static final Duration LATE = Duration.ofHours(3);

    private static KafkaProducer<String, String> producer;

    static {
        createTopics(AT_RISK_TOPIC, STATUS_TOPIC);
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
    private MeterRegistry registry;

    /**
     * <strong>릴레이를 끈다 — 이 클래스는 발행을 보지 않는다.</strong>
     *
     * <p>outbox 리더십은 같은 DB 의 advisory lock <em>하나</em>다(ADR-027 후속 정정). 켜 둔 채로
     * 두면 이 클래스가 리더를 가져가 실제로 발행을 보는 IT 들이 조용히 팔로워가 된다.
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
    void 닿은_stop_이_없으면_개정이_오르지_않는다() {
        // 모름은 0 이 아니다 (ADR-048 결정 1). 편차를 0 으로 두고 돌리면 출발 지연 라우트가
        // 「이득 없음」으로 조용히 닫히고 그 사실이 no-gain 과 구별되지 않는다.
        Planned planned = plannedRoute();
        double before = replanCount(Outcome.NO_ANCHOR);

        sendAtRisk(planned, LATE.toSeconds());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(replanCount(Outcome.NO_ANCHOR)).isEqualTo(before + 1.0d));
        assertThat(revisionOf(planned.routeId())).isEqualTo(1);
        assertThat(lastReplannedAt(planned.routeId()))
                .as("쿨다운은 집었다 — 아무것도 안 했더라도 다시 시도할 값은 없다")
                .isNotNull();
    }

    @Test
    void 쿨다운이_두_번째_at_risk_를_막는다() {
        // 두 at-risk 는 eventId 가 달라 processed_events 가 막지 못한다 (ADR-046 결정 3).
        // 「쿨다운은 이미 있으니 됐다」가 이 자리의 함정이다.
        Planned planned = plannedRoute();
        double before = replanCount(Outcome.COOLDOWN);

        sendAtRisk(planned, LATE.toSeconds());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(lastReplannedAt(planned.routeId())).isNotNull());
        Instant first = lastReplannedAt(planned.routeId());

        sendAtRisk(planned, LATE.toSeconds());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(replanCount(Outcome.COOLDOWN)).isEqualTo(before + 1.0d));
        assertThat(lastReplannedAt(planned.routeId()))
                .as("막힌 쪽은 쿨다운을 다시 집지 않는다 — 집으면 창이 영원히 뒤로 밀린다")
                .isEqualTo(first);
    }

    @Test
    void 페이로드의_편차와_갈리면_센다() {
        // 페이로드는 입력이 아니라 대조값이다 (ADR-048 결정 2). 갈린다는 것은 tracking 과
        // dispatch 가 같은 라우트를 다르게 보고 있다는 뜻이고, 그 사실이 먼저 필요하다.
        Planned planned = plannedRoute();
        arriveLate(planned);
        double before = mismatchCount();

        sendAtRisk(planned, 0L);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(mismatchCount()).isEqualTo(before + 1.0d));
    }

    @Test
    void 자기_편차와_같은_페이로드는_세지_않는다() {
        // 위 테스트의 음성 짝이다 — 카운터를 올린 것이 «갈림» 이지 at-risk 그 자체가 아니다.
        Planned planned = plannedRoute();
        arriveLate(planned);
        double before = mismatchCount();

        sendAtRisk(planned, deviationSeconds(planned));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(lastReplannedAt(planned.routeId())).isNotNull());
        assertThat(mismatchCount()).isEqualTo(before);
    }

    @Test
    void 닿은_시각이_있으면_다섯_갈래_중_하나로_끝나고_소비는_성공한다() {
        // 실패를 DLQ 로 보내지 않는다 — 「후보가 없다」는 재시도로 달라지지 않고 사람이 열어도
        // 할 일이 없다 (ADR-048 결정 5). 합이 곧 트리거 수여야 그 자리가 성립한다.
        Planned planned = plannedRoute();
        arriveLate(planned);
        double before = replanTotal();

        sendAtRisk(planned, deviationSeconds(planned));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(replanTotal()).isEqualTo(before + 1.0d));
        assertThat(replanCount(Outcome.NO_ANCHOR))
                .as("닿은 stop 이 있으므로 이 갈래는 아니다").isZero();
        assertThat(processedCount()).as("소비는 성공한다").isEqualTo(2L);
    }

    @Test
    void 지각이_보이면_옮기고_두_라우트의_개정을_함께_올린다() {
        // 이 IT 의 심장이다 — moveOrder 와 rewrite 가 «실물 SQL» 을 지난다. 단위 테스트의
        // 페이크는 그 두 문장을 흉내 낼 뿐이고, 지점 키로 stop 을 찾는 rewrite 는 흉내와
        // 실물이 갈릴 수 있는 자리다.
        Planned planned = plannedRoute();
        UUID spare = otherRoute(planned.routeId());
        tightenWindows(planned.routeId());
        arriveLate(planned);
        // 미터 레지스트리는 클래스 하나의 컨텍스트를 함께 쓴다 — 절댓값으로 재면 실행 순서가
        // 어설션이 된다. 그리고 순서는 테스트가 말하는 것이 아니다.
        double before = replanCount(Outcome.APPLIED);

        sendAtRisk(planned, deviationSeconds(planned));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(replanCount(Outcome.APPLIED)).isEqualTo(before + 1.0d));
        assertThat(revisionOf(planned.routeId())).as("떠난 쪽도 개정이 오른다").isEqualTo(2);
        assertThat(revisionOf(spare)).as("받은 쪽도 개정이 오른다").isEqualTo(2);
        assertThat(explanationCount())
                .as("운영자가 가장 많이 묻는 자리다 — 설명이 없으면 답이 없다")
                .isPositive();
    }

    @Test
    void 옮겨간_주문의_뒤늦은_완료는_옮겨간_라우트에_적용된다() {
        // ADR-047 재검토 지점 ① — 그 ADR 이 「근거: 추정」으로 적어 둔 자리다. 5-3 이 처음으로
        // 재현 수단을 준다: 기사는 옛 계획의 번호로 찍고, 그 사실이 새 라우트에 적용되어야 한다.
        Planned planned = plannedRoute();
        tightenWindows(planned.routeId());
        arriveLate(planned);
        double applied = replanCount(Outcome.APPLIED);
        sendAtRisk(planned, deviationSeconds(planned));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(replanCount(Outcome.APPLIED)).isEqualTo(applied + 1.0d));

        RouteView.StopView moved = movedStop(planned);
        double before = relocateCount();
        sendStatus(planned.routeId(), moved.seq(), moved.orderIds(),
                PlanningClock.PLAN_AT.plus(LATE));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(relocateCount()).isEqualTo(before + 1.0d));
        assertThat(statusOfOrder(moved.orderIds().getFirst()))
                .as("옛 라우트에서 찍힌 사실이 버려지면 새 기사가 이미 배송된 곳으로 간다")
                .isEqualTo("COMPLETED");
        assertThat(routeOfOrder(moved.orderIds().getFirst()))
                .as("적용된 자리는 지금 있는 라우트다").isNotEqualTo(planned.routeId());
    }

    // --- 보내기 --------------------------------------------------------------

    private void sendAtRisk(Planned planned, long deviationSeconds) {
        RouteView.StopView last = planned.stops().getLast();
        String json = """
                {"eventId":"%s","eventType":"delivery.at-risk","schemaVersion":1,
                 "occurredAt":"%s","producer":"tracking-service","partitionKey":"%s",
                 "payload":{"routeId":"%s","campId":"%s","detectedAt":"%s",
                            "deviationSeconds":%d,
                            "remainingStops":[{"seq":%d,"orderIds":[%s],"etaAt":"%s",
                                               "promisedEnd":"%s","atRisk":true}]}}
                """.formatted(Ids.newId(), PlanningClock.PLAN_AT, planned.routeId(),
                planned.routeId(), CAMP_ID, PlanningClock.PLAN_AT, deviationSeconds,
                last.seq(), quoted(last.orderIds()),
                PlanningClock.PLAN_AT.plus(LATE), PlanningClock.PLAN_AT.plus(LATE));
        EventContracts.load().validateRecord(json);
        producer.send(new ProducerRecord<>(AT_RISK_TOPIC, planned.routeId().toString(), json));
        producer.flush();
    }

    /** 첫 stop 에 {@link #LATE} 만큼 늦게 닿았다고 브로커로 알린다 — 실물 경로 그대로다. */
    private void arriveLate(Planned planned) {
        RouteView.StopView first = planned.stops().getFirst();
        Instant actualAt = first.plannedArrival().plus(LATE);

        sendStatus(planned.routeId(), first.seq(), first.orderIds(), actualAt);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(actualAtOf(planned.routeId(), first.seq())).isEqualTo(actualAt));
    }

    private void sendStatus(UUID routeId, int seq, List<UUID> orderIds, Instant occurredAt) {
        String json = """
                {"eventId":"%s","eventType":"delivery.status","schemaVersion":1,
                 "occurredAt":"%s","producer":"tracking-service","partitionKey":"%s",
                 "payload":{"routeId":"%s","stopSeq":%d,"orderIds":[%s],"status":"COMPLETED",
                            "occurredAt":"%s"}}
                """.formatted(Ids.newId(), PlanningClock.PLAN_AT, routeId, routeId, seq,
                quoted(orderIds), occurredAt);
        EventContracts.load().validateRecord(json);
        producer.send(new ProducerRecord<>(STATUS_TOPIC, routeId.toString(), json));
        producer.flush();
    }

    private static String quoted(List<UUID> ids) {
        return ids.stream().map(id -> "\"" + id + "\"").reduce((a, b) -> a + "," + b).orElseThrow();
    }

    // --- 조회 ----------------------------------------------------------------

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private double replanCount(Outcome outcome) {
        return registry.counter(DawnlineMetrics.REPLAN.meterName(), DispatchMetrics.TAG_OUTCOME,
                outcome.label()).count();
    }

    private double replanTotal() {
        double total = 0.0d;
        for (Outcome outcome : Outcome.values()) {
            total += replanCount(outcome);
        }
        return total;
    }

    private double mismatchCount() {
        return registry.counter(DawnlineMetrics.AT_RISK_DEVIATION_MISMATCH.meterName()).count();
    }

    private Instant lastReplannedAt(UUID routeId) {
        return tx().execute(status -> (Instant) entityManager.createNativeQuery(
                        "SELECT last_replanned_at FROM routes WHERE id = ?")
                .setParameter(1, routeId).getSingleResult());
    }

    private int revisionOf(UUID routeId) {
        return ((Number) tx().execute(status -> entityManager.createNativeQuery(
                        "SELECT revision FROM routes WHERE id = ?")
                .setParameter(1, routeId).getSingleResult())).intValue();
    }

    private Instant actualAtOf(UUID routeId, int seq) {
        return tx().execute(status -> (Instant) entityManager.createNativeQuery(
                        "SELECT actual_at FROM route_stops WHERE route_id = ? AND seq = ?")
                .setParameter(1, routeId).setParameter(2, seq).getSingleResult());
    }

    /** dispatch 가 자기 테이블에서 계산하는 값 — 페이로드에 실어 보낼 «같은» 편차다. */
    private long deviationSeconds(Planned planned) {
        RouteView.StopView first = planned.stops().getFirst();
        return Duration.between(first.plannedArrival(),
                actualAtOf(planned.routeId(), first.seq())).toSeconds();
    }

    private double relocateCount() {
        return registry.counter(DawnlineMetrics.STATUS_AFTER_RELOCATE.meterName()).count();
    }

    private long explanationCount() {
        return ((Number) tx().execute(status -> entityManager.createNativeQuery(
                "SELECT count(*) FROM plan_explanations WHERE rule_name = 'AT_RISK_RELOCATE'")
                .getSingleResult())).longValue();
    }

    private String statusOfOrder(UUID orderId) {
        return tx().execute(status -> (String) entityManager.createNativeQuery("""
                SELECT s.status FROM route_stops s
                  JOIN route_stop_orders o ON o.stop_id = s.id
                 WHERE o.order_id = ?
                """).setParameter(1, orderId).getSingleResult());
    }

    private UUID routeOfOrder(UUID orderId) {
        return tx().execute(status -> (UUID) entityManager.createNativeQuery("""
                SELECT s.route_id FROM route_stops s
                  JOIN route_stop_orders o ON o.stop_id = s.id
                 WHERE o.order_id = ?
                """).setParameter(1, orderId).getSingleResult());
    }

    /** 재계획이 이 라우트에서 내보낸 stop — 옛 좌표 그대로 들고 있다. */
    private RouteView.StopView movedStop(Planned planned) {
        return planned.stops().stream()
                .filter(stop -> !routeOfOrder(stop.orderIds().getFirst())
                        .equals(planned.routeId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("옮겨간 stop 이 없다 — 앞의 전제가 깨졌다"));
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
        seedCandidates(waveId, 24);
        runPlan.run(RunPlanCommand.of(waveId, CAMP_ID, CAMP, null));
        PlanView plan = tx().execute(status -> planQueries.findPlanByWave(waveId)).orElseThrow();
        assertThat(plan.routes()).as("재계획에는 받을 라우트가 있어야 한다").hasSizeGreaterThan(1);
        UUID routeId = plan.routes().getFirst().routeId();
        RouteView route = tx().execute(status -> planQueries.findRoute(routeId)).orElseThrow();
        assertThat(route.stops()).as("이 테스트는 stop 둘 이상을 전제한다").hasSizeGreaterThan(1);
        return new Planned(routeId, route.stops());
    }

    /** 같은 계획의 다른 라우트 하나. */
    private UUID otherRoute(UUID routeId) {
        return tx().execute(status -> (UUID) entityManager.createNativeQuery("""
                SELECT r.id FROM routes r
                 WHERE r.plan_id = (SELECT plan_id FROM routes WHERE id = ?) AND r.id <> ?
                 ORDER BY r.seq_no LIMIT 1
                """).setParameter(1, routeId).setParameter(2, routeId).getSingleResult());
    }

    /**
     * 이 라우트가 실은 주문들의 약속창만 좁힌다 — <strong>계획을 돌린 뒤에</strong>.
     *
     * <p>처음부터 좁게 두면 계획 자체가 달라진다(미배정이 늘고 라우트가 쪼개진다). 여기서 보려는
     * 것은 「세 시간 늦은 기사에게 지각이 보이는가」이지 「좁은 창에서 어떻게 계획하는가」가 아니다.
     * 받는 쪽 라우트의 창은 그대로 두는 것이 이 픽스처의 핵심이다 — 그래야 옮길 값어치가 생긴다.
     *
     * @param routeId 늦은 라우트
     */
    private void tightenWindows(UUID routeId) {
        tx().executeWithoutResult(status -> entityManager.createNativeQuery("""
                UPDATE dispatch_candidates SET promised_end = ?
                 WHERE order_id IN (SELECT o.order_id FROM route_stop_orders o
                                      JOIN route_stops s ON s.id = o.stop_id
                                     WHERE s.route_id = ?)
                """).setParameter(1, PlanningClock.PLAN_AT.plus(Duration.ofMinutes(90)))
                .setParameter(2, routeId).executeUpdate());
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
                        GeoPoint.of(CAMP.lat() + 0.006d * (i % 6 + 1),
                                CAMP.lng() + 0.007d * (i / 6 + 1)),
                        90_000, 180_000, false, false, window, 60, false, 0, now));
            }
        });
        return orderIds;
    }
}
