package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.DispatchMetrics;
import com.dawnline.dispatch.application.port.in.PlanView;
import com.dawnline.dispatch.application.port.in.RecordDeliveryStatusUseCase;
import com.dawnline.dispatch.application.port.in.RecordDeliveryStatusUseCase.DeliveryStatusCommand;
import com.dawnline.dispatch.application.port.in.ReplanRouteUseCase.Outcome;
import com.dawnline.dispatch.application.port.in.RouteView;
import com.dawnline.dispatch.application.port.in.RunPlanCommand;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.application.port.out.DispatchEvents;
import com.dawnline.dispatch.application.port.out.PlanQueries;
import com.dawnline.dispatch.config.DispatchProperties;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.dispatch.domain.RouteStopStatus;
import com.dawnline.dispatch.domain.optimizer.DistanceProvider;
import com.dawnline.dispatch.domain.optimizer.HaversineDistance;
import com.dawnline.messaging.contract.EventContracts;
import com.dawnline.observability.DawnlineMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 재계획과 같은 순간에 도착한 배송 사실이 사라지지 않는다 (DESIGN.md §6.8, ADR-048 후속).
 *
 * <p>두 번째 {@code peak-day}(2026-09-27)에서 드러났다: tracking 에서는 주문이 {@code COMPLETED} 인데 dispatch 에서는 그 주문이
 * 다른 라우트의 {@code PLANNED} stop 에 있었다 — 비활성화가 끝나지 않은 차량 4대, 16건. 재계획이 옮긴 stop 에 그 순간 도착한
 * 상태가 적힐 자리를 잃었다. 경합은 두 순서로 일어나고 이 클래스가 둘을 각각 멈춰 세운다.
 *
 * <ol>
 *   <li><strong>계산하는 동안 끝난 배송</strong> — 재계획이 {@code PLANNED} 로 읽은 stop 이 쓰기 전에 {@code COMPLETED} 가 된다.
 *       쓰기가 그 stop 을 옮기면 사실이 옛 자리에 남고 새 자리는 {@code PLANNED} 다. 거리 제공자를 감싸 계산 안에서 멈춘다.</li>
 *   <li><strong>쓰기가 잡은 stop 에 도착한 배송</strong> — 상태 반영이 옛 자리를 읽은 뒤 쓰기의 락을 기다린다. 쓰기가 그 행을
 *       지우면 반영은 0 행을 고치고 조용히 끝난다. 개정 발행({@code routeRevised})을 감싸 쓰기 트랜잭션 안에서 멈춘다.</li>
 * </ol>
 *
 * <p>셋째는 7-0 B13 이다 — 재계획의 계산이 트랜잭션 안에 있으면 1 의 창이 계산 시간만큼 열려 있고 그동안 커넥션을 쥔다.
 *
 * <p>상태 반영은 브로커를 지나지 않고 유스케이스를 직접 부른다 — 보려는 것은 두 트랜잭션의 순서이고, 브로커는 그 순서를 흐린다.
 * 재계획은 브로커로 부른다: 멱등 게이트가 쓰기를 감싸는 모양(ADR-064 결정 2)까지 지나야 한다.
 */
@SpringBootTest(classes = DispatchApplication.class)
@Import({PlanningClock.class, ReplanRaceIT.Gates.class})
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("ReplanRaceIT — 재계획과 동시에 도착한 배송")
class ReplanRaceIT extends DispatchIntegrationTestBase {

    /** 시드의 첫 캠프 (서울 북부) — {@code ReplanIT} 와 같다. */
    private static final UUID CAMP_ID = UUID.fromString("01a06edd-6c00-7000-8001-000000000001");
    private static final GeoPoint CAMP = GeoPoint.of(37.640000, 127.030000);
    private static final String AT_RISK_TOPIC = "dawnline.delivery.at-risk.v1";
    private static final Duration LATE = Duration.ofHours(3);

    /** 이 DB 에서 트랜잭션을 연 채 쉬는 백엔드 — {@code PlanComputeConnectionIT} 와 같다. */
    private static final String IDLE_IN_TX_SQL = """
            SELECT count(*) FROM pg_stat_activity
             WHERE datname = current_database() AND pid <> pg_backend_pid()
               AND state = 'idle in transaction'
            """;

    /** 행 락을 기다리는 백엔드. */
    private static final String LOCK_WAITERS_SQL = """
            SELECT count(*) FROM pg_stat_activity
             WHERE datname = current_database() AND wait_event_type = 'Lock'
            """;

    private static KafkaProducer<String, String> producer;

    /** 이 컨텍스트가 받은 at-risk 수의 기대값 — 끝난 재계획의 합이 여기에 닿아야 다음 테스트가 정리를 시작한다. */
    private double sentReplans;

    static {
        createTopics(AT_RISK_TOPIC);
    }

    /**
     * 재계획을 두 자리에서 멈춘다 — 계산 안(거리를 처음 물을 때)과 쓰기 안(개정을 처음 발행할 때). 무장했을 때만 한 번씩.
     */
    @TestConfiguration
    static class Gates {

        static final AtomicBoolean COMPUTE_ARMED = new AtomicBoolean();
        static volatile CountDownLatch computeEntered = new CountDownLatch(1);
        static volatile CountDownLatch computeRelease = new CountDownLatch(1);

        static final AtomicBoolean WRITE_ARMED = new AtomicBoolean();
        static volatile CountDownLatch writeEntered = new CountDownLatch(1);
        static volatile CountDownLatch writeRelease = new CountDownLatch(1);

        @Bean
        @Primary
        DistanceProvider gatedDistance(DispatchProperties properties) {
            DistanceProvider real = new HaversineDistance(properties.distance().roadFactor(),
                    properties.distance().averageSpeedKmh());
            return (from, to) -> {
                if (COMPUTE_ARMED.compareAndSet(true, false)) {
                    pause(computeEntered, computeRelease);
                }
                return real.between(from, to);
            };
        }

        @Bean
        static BeanPostProcessor gatedEvents() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DispatchEvents real)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(DispatchEvents.class.getClassLoader(),
                            new Class<?>[] {DispatchEvents.class}, (proxy, method, args) -> {
                                if (method.getName().equals("routeRevised") && WRITE_ARMED.compareAndSet(true, false)) {
                                    pause(writeEntered, writeRelease);
                                }
                                try {
                                    return method.invoke(real, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            });
                }
            };
        }

        private static void pause(CountDownLatch entered, CountDownLatch release) {
            entered.countDown();
            try {
                if (!release.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("테스트가 재계획을 풀어 주지 않았다");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    @Autowired
    private RunPlanUseCase runPlan;

    @Autowired
    private RecordDeliveryStatusUseCase deliveryStatus;

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
     * 릴레이를 끈다 — 이 클래스는 발행을 보지 않는다({@code ReplanIT} 와 같은 이유: advisory lock 하나를 가져가지 않는다).
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
                    "route_plans", "dispatch_candidates", "outbox_events", "processed_events"}) {
                entityManager.createNativeQuery("DELETE FROM " + table).executeUpdate();
            }
        });
        Gates.computeEntered = new CountDownLatch(1);
        Gates.computeRelease = new CountDownLatch(1);
        Gates.writeEntered = new CountDownLatch(1);
        Gates.writeRelease = new CountDownLatch(1);
    }

    /**
     * 풀어 주고, 보낸 재계획이 끝날 때까지 기다린다 — 어설션이 먼저 실패하면 멈춰 있던 재계획이 다음 테스트의 정리와 겹친다
     * (2026-09-27 첫 실행: 셋째 테스트의 {@code DELETE FROM routes} 가 앞 테스트의 쓰기와 교착했다).
     */
    @AfterEach
    void disarm() {
        Gates.COMPUTE_ARMED.set(false);
        Gates.WRITE_ARMED.set(false);
        Gates.computeRelease.countDown();
        Gates.writeRelease.countDown();
        await().atMost(Duration.ofSeconds(30)).until(() -> replanTotal() >= sentReplans);
    }

    @Test
    void 계산하는_동안_끝난_배송은_재계획이_옮기지_않는다() throws Exception {
        Planned planned = lateRoute();
        double before = replanTotal();
        double stale = replanCount(Outcome.STALE);
        Gates.COMPUTE_ARMED.set(true);

        sendAtRisk(planned);
        assertThat(Gates.computeEntered.await(30, TimeUnit.SECONDS)).as("재계획이 계산 안에서 멈췄다").isTrue();

        // 계산이 PLANNED 로 읽은 남은 stop 전부가 지금 끝난다 — 각각 자기 트랜잭션으로 커밋된다.
        List<UUID> completed = new ArrayList<>();
        for (RouteView.StopView stop : planned.remaining()) {
            complete(planned.routeId(), stop);
            completed.addAll(stop.orderIds());
        }
        Gates.computeRelease.countDown();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(replanTotal()).as("재계획이 끝났다").isEqualTo(before + 1.0d));
        assertThat(plannedHolding(completed))
                .as("끝난 배송의 주문이 PLANNED stop 에 있다 — tracking 은 COMPLETED 인데 dispatch 는 다시 보내려 한다 (검증 표 V8)")
                .isEmpty();
        assertThat(replanCount(Outcome.STALE)).as("쓰기가 다시 보고 결과를 버렸다 (ADR-068 결정 2)").isEqualTo(stale + 1.0d);
        assertThat(completed.stream().filter(orderId -> !routeOf(orderId).equals(planned.routeId())).toList())
                .as("아무것도 옮기지 않았다").isEmpty();
    }

    @Test
    void 쓰기가_잡은_stop_에_도착한_배송은_옮겨간_행에_남는다() throws Exception {
        Planned planned = lateRoute();
        double applied = replanCount(Outcome.APPLIED);
        Gates.WRITE_ARMED.set(true);

        sendAtRisk(planned);
        assertThat(Gates.writeEntered.await(30, TimeUnit.SECONDS)).as("재계획이 쓰기 트랜잭션 안에서 멈췄다").isTrue();

        // 반영 하나가 스레드 하나다 — 각자 옛 자리를 읽고 쓰기의 락을 기다려야 한다. 한 스레드로 차례로 부르면 첫 번째 뒤의 것은
        // 커밋 뒤에 읽어 새 자리를 찾고, 경합이 첫 stop 하나로 줄어든다.
        List<UUID> completed = new ArrayList<>();
        List<Future<?>> reports = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(planned.remaining().size())) {
            for (RouteView.StopView stop : planned.remaining()) {
                completed.addAll(stop.orderIds());
                reports.add(pool.submit(() -> complete(planned.routeId(), stop)));
            }
            // 전제를 먼저 말한다 — 반영 중 하나 이상이 쓰기의 락을 기다린다. 아니면 아래 어설션은 경합이 없는 순서를 본 것이다.
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                    .until(() -> count(LOCK_WAITERS_SQL) >= 1L);
            Gates.writeRelease.countDown();
            for (Future<?> report : reports) {
                report.get(30, TimeUnit.SECONDS);
            }
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(replanCount(Outcome.APPLIED)).as("재계획은 옮겼다").isEqualTo(applied + 1.0d));
        assertThat(completed.stream().filter(orderId -> !routeOf(orderId).equals(planned.routeId())).toList())
                .as("옮겨 간 주문이 있다 — 없으면 이 테스트는 경합이 닿지 않는 stop 만 본 것이다")
                .isNotEmpty();
        assertThat(plannedHolding(completed))
                .as("쓰기의 락을 기다린 반영이 사라졌다 — 기다린 행이 지워졌다 (검증 표 V8)")
                .isEmpty();
    }

    @Test
    void 재계획의_계산_중에는_트랜잭션이_없다() throws Exception {
        // 7-0 B13 — ADR-064 가 계획에 한 것을 재계획에. 계산 동안 트랜잭션이 열려 있으면 커넥션 하나가 빠지고, 첫 테스트의 창이
        // 계산 시간만큼 열린다.
        Planned planned = lateRoute();
        double before = replanTotal();
        Gates.COMPUTE_ARMED.set(true);

        sendAtRisk(planned);
        assertThat(Gates.computeEntered.await(30, TimeUnit.SECONDS)).as("재계획이 계산 안에서 멈췄다").isTrue();
        assertThat(replanTotal()).as("아직 끝나지 않았다 — 멈춘 것은 계산 도중이다").isEqualTo(before);

        long held = 0L;
        for (int i = 0; i < 5 && held == 0L; i++) {
            held = count(IDLE_IN_TX_SQL);
            Thread.sleep(200);
        }
        assertThat(held).as("계산 중에 트랜잭션을 연 채 쉬는 백엔드").isZero();

        Gates.computeRelease.countDown();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(replanTotal()).as("풀어 준 재계획은 끝까지 간다").isEqualTo(before + 1.0d));
    }

    // --- 보내기 --------------------------------------------------------------

    private void sendAtRisk(Planned planned) {
        sentReplans = replanTotal() + 1.0d;
        RouteView.StopView last = planned.stops().getLast();
        String json = """
                {"eventId":"%s","eventType":"delivery.at-risk","schemaVersion":1,
                 "occurredAt":"%s","producer":"tracking-service","partitionKey":"%s",
                 "payload":{"routeId":"%s","campId":"%s","detectedAt":"%s",
                            "deviationSeconds":%d,
                            "remainingStops":[{"seq":%d,"orderIds":[%s],"etaAt":"%s",
                                               "promisedEnd":"%s","atRisk":true}]}}
                """.formatted(Ids.newId(), PlanningClock.PLAN_AT, planned.routeId(),
                planned.routeId(), CAMP_ID, PlanningClock.PLAN_AT, LATE.toSeconds(),
                last.seq(), quoted(last.orderIds()),
                PlanningClock.PLAN_AT.plus(LATE), PlanningClock.PLAN_AT.plus(LATE));
        EventContracts.load().validateRecord(json);
        producer.send(new ProducerRecord<>(AT_RISK_TOPIC, planned.routeId().toString(), json));
        producer.flush();
    }

    /** 기사가 옛 개정의 번호로 찍은 완료 — tracking 이 낸 {@code delivery.status} 그대로다. */
    private void complete(UUID routeId, RouteView.StopView stop) {
        deliveryStatus.record(new DeliveryStatusCommand(routeId, stop.seq(), stop.orderIds(),
                RouteStopStatus.COMPLETED, stop.plannedArrival().plus(LATE)));
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

    /** 풀 밖의 연결로 센다 — 둘째 테스트의 반영 스레드들이 풀을 다 잡은 순간에도 관측은 돌아야 한다. */
    private static long count(String sql) throws SQLException {
        try (Connection c = unpooledConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 이 주문들 가운데 지금 {@code PLANNED} stop 에 있는 것 — 검증 표 V8 의 dispatch 쪽 모양이다. */
    @SuppressWarnings("unchecked")
    private List<UUID> plannedHolding(List<UUID> orderIds) {
        return tx().execute(status -> entityManager.createNativeQuery("""
                SELECT o.order_id FROM route_stop_orders o
                  JOIN route_stops s ON s.id = o.stop_id
                 WHERE o.order_id = ANY(?) AND s.status = 'PLANNED'
                 ORDER BY 1
                """).setParameter(1, orderIds.toArray(UUID[]::new)).getResultList());
    }

    private UUID routeOf(UUID orderId) {
        return tx().execute(status -> (UUID) entityManager.createNativeQuery("""
                SELECT s.route_id FROM route_stops s
                  JOIN route_stop_orders o ON o.stop_id = s.id
                 WHERE o.order_id = ?
                """).setParameter(1, orderId).getSingleResult());
    }

    private Instant actualAtOf(UUID routeId, int seq) {
        return tx().execute(status -> (Instant) entityManager.createNativeQuery(
                        "SELECT actual_at FROM route_stops WHERE route_id = ? AND seq = ?")
                .setParameter(1, routeId).setParameter(2, seq).getSingleResult());
    }

    // --- 픽스처 --------------------------------------------------------------

    /**
     * @param routeId   늦은 라우트
     * @param stops     계획 때의 stop 들
     */
    private record Planned(UUID routeId, List<RouteView.StopView> stops) {

        /** 첫 stop 은 닿았다 — 나머지. */
        List<RouteView.StopView> remaining() {
            return stops.subList(1, stops.size());
        }
    }

    /**
     * 세 시간 늦게 첫 stop 에 닿은 라우트 — 남은 stop 들의 약속창이 좁아 옮길 값어치가 있다({@code ReplanIT} 의
     * {@code 지각이_보이면_옮기고_두_라우트의_개정을_함께_올린다} 와 같은 픽스처).
     */
    private Planned lateRoute() {
        UUID waveId = Ids.newId();
        seedCandidates(waveId, 24);
        runPlan.run(RunPlanCommand.of(waveId, CAMP_ID, CAMP, null));
        PlanView plan = tx().execute(status -> planQueries.findPlanByWave(waveId)).orElseThrow();
        assertThat(plan.routes()).as("재계획에는 받을 라우트가 있어야 한다").hasSizeGreaterThan(1);
        UUID routeId = plan.routes().getFirst().routeId();
        RouteView route = tx().execute(status -> planQueries.findRoute(routeId)).orElseThrow();
        assertThat(route.stops()).as("이 테스트는 stop 둘 이상을 전제한다").hasSizeGreaterThan(1);
        Planned planned = new Planned(routeId, route.stops());

        tx().executeWithoutResult(status -> entityManager.createNativeQuery("""
                UPDATE dispatch_candidates SET promised_end = ?
                 WHERE order_id IN (SELECT o.order_id FROM route_stop_orders o
                                      JOIN route_stops s ON s.id = o.stop_id
                                     WHERE s.route_id = ?)
                """).setParameter(1, PlanningClock.PLAN_AT.plus(Duration.ofMinutes(90)))
                .setParameter(2, routeId).executeUpdate());

        RouteView.StopView first = planned.stops().getFirst();
        complete(routeId, first);
        assertThat(actualAtOf(routeId, first.seq())).as("첫 stop 에 늦게 닿았다 — 재계획의 편차").isNotNull();
        return planned;
    }

    /** 약속창의 기준을 {@link PlanningClock#PLAN_AT} 에서 잡는다 — {@code ReplanIT} 와 같다. */
    private void seedCandidates(UUID waveId, int count) {
        Instant now = PlanningClock.PLAN_AT.truncatedTo(ChronoUnit.MICROS);
        TimeWindow window = new TimeWindow(now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(5)));
        tx().executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                candidates.insertIfAbsent(DispatchCandidate.load(Ids.newId(), waveId, CAMP_ID, null,
                        GeoPoint.of(CAMP.lat() + 0.006d * (i % 6 + 1), CAMP.lng() + 0.007d * (i / 6 + 1)),
                        90_000, 180_000, false, false, window, 60, false, 0, now));
            }
        });
    }
}
