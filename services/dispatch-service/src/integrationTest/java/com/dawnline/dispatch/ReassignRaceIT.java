package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.common.error.DomainException;
import com.dawnline.dispatch.application.port.in.PlanView;
import com.dawnline.dispatch.application.port.in.ReassignStopUseCase;
import com.dawnline.dispatch.application.port.in.RouteView;
import com.dawnline.dispatch.application.port.in.RunPlanCommand;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.application.port.out.DispatchEvents;
import com.dawnline.dispatch.application.port.out.PlanQueries;
import com.dawnline.dispatch.domain.DispatchCandidate;
import jakarta.persistence.EntityManager;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 재배정의 쓰기와 같은 순간의 다른 쓰기 (DESIGN.md §5.3, ADR-068 후속 C) — {@code ReplanRaceIT} 의 거울.
 *
 * <p>옮길 stop 은 #86 부터 {@code FOR UPDATE} 로 잡고 다시 봤다({@code lockStopOf}). 이 클래스가 보는 것은 <strong>받는 쪽</strong>이다
 * — 받을 라우트, 붙을 stop, 두 라우트 행. 경합은 다른 트랜잭션이 행을 먼저 잡은 채 멈추는 것으로 만든다: 풀 밖의 연결이 커밋하지 않은
 * 상태 반영을 들고 있거나({@link #held}), 재배정 하나가 발행 직전에 멈춘다({@link Gates}).
 */
@SpringBootTest(classes = DispatchApplication.class)
@Import({PlanningClock.class, ReassignRaceIT.Gates.class})
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("ReassignRaceIT — 재배정과 동시에 일어난 쓰기")
class ReassignRaceIT extends DispatchIntegrationTestBase {

    /** 시드의 첫 캠프 (서울 북부) — {@code DispatchAdminIT} 와 같다. */
    private static final UUID CAMP_ID = UUID.fromString("01a06edd-6c00-7000-8001-000000000001");
    private static final GeoPoint CAMP = GeoPoint.of(37.640000, 127.030000);

    /** 행 락을 기다리는 백엔드. */
    private static final String LOCK_WAITERS_SQL = """
            SELECT count(*) FROM pg_stat_activity
             WHERE datname = current_database() AND wait_event_type = 'Lock'
            """;

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    /** 재배정을 발행 직전(쓰기 트랜잭션 안, 두 라우트를 다 쓴 뒤)에 멈춘다 — 무장했을 때만 한 번. */
    @TestConfiguration
    static class Gates {

        static final AtomicBoolean WRITE_ARMED = new AtomicBoolean();
        static volatile CountDownLatch writeEntered = new CountDownLatch(1);
        static volatile CountDownLatch writeRelease = new CountDownLatch(1);

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
                                if (method.getName().equals("routeAssigned") && WRITE_ARMED.compareAndSet(true, false)) {
                                    writeEntered.countDown();
                                    if (!writeRelease.await(60, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("테스트가 재배정을 풀어 주지 않았다");
                                    }
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
    }

    @Autowired
    private RunPlanUseCase runPlan;

    @Autowired
    private ReassignStopUseCase reassign;

    @Autowired
    private PlanQueries planQueries;

    @Autowired
    private DispatchCandidateRepository candidates;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    /**
     * 릴레이를 끈다 — 발행은 {@code outbox_events} 행으로 본다({@code DispatchAdminIT} 와 같은 이유).
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void relayOff(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    @BeforeEach
    void clean() {
        tx().executeWithoutResult(status -> {
            for (String table : new String[] {"plan_explanations", "route_stop_orders", "route_stops", "routes",
                    "route_plans", "dispatch_candidates", "outbox_events", "processed_events"}) {
                entityManager.createNativeQuery("DELETE FROM " + table).executeUpdate();
            }
        });
        Gates.writeEntered = new CountDownLatch(1);
        Gates.writeRelease = new CountDownLatch(1);
    }

    /** 풀어 주고 스레드를 거둔다 — 어설션이 먼저 실패해도 멈춘 쓰기가 다음 테스트의 정리와 겹치지 않는다. */
    @AfterEach
    void disarm() throws InterruptedException {
        Gates.WRITE_ARMED.set(false);
        Gates.writeRelease.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).as("멈춘 쓰기가 끝났다").isTrue();
    }

    // ---------------------------------------------------------------- 받을 라우트

    @Test
    void 끝난_라우트로는_옮기지_않는다() {
        Plan plan = plan();
        UUID orderId = plan.from().stops().getFirst().orderIds().getFirst();
        finishAll(plan.to());

        String outcome = outcome(() -> reassign.reassign(plan.from().routeId(), orderId, plan.to().routeId()));

        assertThat(outcome).as("끝난 라우트의 기사는 이미 돌아왔다 — 받은 주문은 배송되지 않는다").isEqualTo("route-finished");
        assertThat(routeOf(orderId)).as("주문은 제자리에").isEqualTo(plan.from().routeId());
    }

    @Test
    void 기다리는_동안_받을_라우트가_끝나면_옮기지_않는다() throws Exception {
        Plan plan = plan();
        UUID orderId = plan.from().stops().getFirst().orderIds().getFirst();
        RouteView.StopView last = plan.to().stops().getLast();
        finishAllBut(plan.to(), last);

        try (Held held = held(last.stopId())) {
            Future<String> reassigned = pool.submit(() ->
                    outcome(() -> reassign.reassign(plan.from().routeId(), orderId, plan.to().routeId())));
            awaitLockWaiter();
            held.commit();
            assertThat(reassigned.get(30, TimeUnit.SECONDS))
                    .as("마지막 stop 이 커밋된 뒤에 다시 봤다 — 받을 라우트는 끝났다").isEqualTo("route-finished");
        }
        assertThat(routeOf(orderId)).isEqualTo(plan.from().routeId());
    }

    // ---------------------------------------------------------------- 붙을 stop

    @Test
    void 같은_지점의_끝난_stop_에는_붙이지_않는다() {
        Plan plan = plan();
        RouteView.StopView merged = mergedStopOf(plan.from());
        UUID orderId = merged.orderIds().getFirst();
        RouteView.StopView visited = plan.to().stops().getFirst();
        moveTo(visited.stopId(), merged);
        tx().executeWithoutResult(status -> entityManager.createNativeQuery(
                "UPDATE route_stops SET status = 'COMPLETED', actual_at = ? WHERE id = ?")
                .setParameter(1, PlanningClock.PLAN_AT).setParameter(2, visited.stopId()).executeUpdate());

        reassign.reassign(plan.from().routeId(), orderId, plan.to().routeId());

        assertThat(statusOfStopHolding(orderId))
                .as("기사가 이미 떠난 지점에 붙으면 그 주문은 배송된 것으로 보이고 배송되지 않는다").isEqualTo("PLANNED");
        assertThat(ordersOfStop(visited.stopId())).as("끝난 stop 의 주문은 그대로").isEqualTo(visited.orderIds());
    }

    @Test
    void 기다리는_동안_붙을_stop_이_끝나면_새_stop_을_만든다() throws Exception {
        Plan plan = plan();
        RouteView.StopView merged = mergedStopOf(plan.from());
        UUID orderId = merged.orderIds().getFirst();
        RouteView.StopView target = plan.to().stops().getFirst();
        moveTo(target.stopId(), merged);

        try (Held held = held(target.stopId())) {
            Future<String> reassigned = pool.submit(() ->
                    outcome(() -> reassign.reassign(plan.from().routeId(), orderId, plan.to().routeId())));
            awaitLockWaiter();
            held.commit();
            assertThat(reassigned.get(30, TimeUnit.SECONDS)).isEqualTo("ok");
        }
        assertThat(statusOfStopHolding(orderId)).as("끝난 stop 이 아니라 새 stop 에").isEqualTo("PLANNED");
        assertThat(ordersOfStop(target.stopId())).isEqualTo(target.orderIds());
    }

    // ---------------------------------------------------------------- 두 라우트 행

    @Test
    void 같은_라우트로_동시에_옮긴_두_재배정은_서로를_지우지_않는다() throws Exception {
        Plan plan = plan();
        UUID first = plan.from().stops().getFirst().orderIds().getFirst();
        UUID second = plan.other().stops().getFirst().orderIds().getFirst();
        Gates.WRITE_ARMED.set(true);

        Future<String> one = pool.submit(() ->
                outcome(() -> reassign.reassign(plan.from().routeId(), first, plan.to().routeId())));
        assertThat(Gates.writeEntered.await(30, TimeUnit.SECONDS)).as("첫 재배정이 발행 직전에 멈췄다").isTrue();
        Future<String> two = pool.submit(() ->
                outcome(() -> reassign.reassign(plan.other().routeId(), second, plan.to().routeId())));
        awaitLockWaiter();
        Gates.writeRelease.countDown();

        assertThat(List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS)))
                .as("둘 다 옮겼다 — 둘째는 첫째가 커밋한 라우트를 읽고 계산했다").containsExactly("ok", "ok");
        RouteView to = tx().execute(status -> planQueries.findRoute(plan.to().routeId())).orElseThrow();
        assertThat(to.stops().stream().flatMap(stop -> stop.orderIds().stream())).contains(first, second);
        assertThat(to.stops().stream().map(RouteView.StopView::seq).toList())
                .isEqualTo(IntStream.rangeClosed(1, to.stops().size()).boxed().toList());
        assertThat(publishedOrders(plan.to().routeId()))
                .as("마지막 개정이 두 주문을 다 싣는다 — tracking 은 이 개정으로 기사의 목록을 바꾼다").contains(first, second);
    }

    // ---------------------------------------------------------------- 도우미

    /** 가장 많이 실은 라우트(from), 둘째(other), 가장 적게 실은 라우트(to). */
    private record Plan(RouteView from, RouteView other, RouteView to) {
    }

    private Plan plan() {
        UUID waveId = Ids.newId();
        seedCandidates(waveId, 120);
        runPlan.run(RunPlanCommand.of(waveId, CAMP_ID, CAMP, null));
        PlanView plan = tx().execute(status -> planQueries.findPlanByWave(waveId)).orElseThrow();
        assertThat(plan.routes()).as("라우트 셋 — 두 곳에서 한 곳으로 옮긴다").hasSizeGreaterThanOrEqualTo(3);
        List<RouteView> byLoad = plan.routes().stream()
                .sorted(Comparator.comparingInt(PlanView.RouteSummary::stopCount).reversed())
                .map(route -> tx().execute(status -> planQueries.findRoute(route.routeId())).orElseThrow())
                .toList();
        return new Plan(byLoad.get(0), byLoad.get(1), byLoad.getLast());
    }

    private static RouteView.StopView mergedStopOf(RouteView route) {
        return route.stops().stream().filter(stop -> stop.orderIds().size() > 1).findFirst()
                .orElseThrow(() -> new IllegalStateException("전제 — 합쳐진 stop 이 있다"));
    }

    /** 받을 라우트의 stop 하나를 옮길 stop 과 같은 지점에 둔다 — 같은 건물을 두 라우트가 도는 날. */
    private void moveTo(UUID stopId, RouteView.StopView at) {
        tx().executeWithoutResult(status -> entityManager.createNativeQuery("""
                UPDATE route_stops SET lat = (SELECT lat FROM route_stops WHERE id = ?),
                                       lng = (SELECT lng FROM route_stops WHERE id = ?)
                 WHERE id = ?
                """).setParameter(1, at.stopId()).setParameter(2, at.stopId()).setParameter(3, stopId).executeUpdate());
    }

    private void finishAll(RouteView route) {
        finishAllBut(route, null);
    }

    private void finishAllBut(RouteView route, RouteView.StopView keep) {
        tx().executeWithoutResult(status -> entityManager.createNativeQuery("""
                UPDATE route_stops SET status = 'COMPLETED', actual_at = ? WHERE route_id = ? AND id <> ?
                """).setParameter(1, PlanningClock.PLAN_AT).setParameter(2, route.routeId())
                .setParameter(3, keep == null ? Ids.newId() : keep.stopId()).executeUpdate());
    }

    /** 풀 밖의 연결이 stop 하나를 {@code COMPLETED} 로 바꾼 채 커밋하지 않는다 — 락을 기다리던 상태 반영의 모양. */
    private static Held held(UUID stopId) throws SQLException {
        Connection connection = unpooledConnection();
        connection.setAutoCommit(false);
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE route_stops SET status = 'COMPLETED', actual_at = now() WHERE id = ?")) {
            update.setObject(1, stopId);
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
        return new Held(connection);
    }

    private record Held(Connection connection) implements AutoCloseable {

        void commit() throws SQLException {
            connection.commit();
        }

        @Override
        public void close() throws SQLException {
            connection.close();
        }
    }

    private static void awaitLockWaiter() {
        // 전제를 먼저 말한다 — 재배정이 락을 기다린다. 아니면 아래 어설션은 경합이 없는 순서를 본 것이다.
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .until(() -> count(LOCK_WAITERS_SQL) >= 1L);
    }

    /** {@code ok} 또는 도메인 예외의 코드 — 스레드 밖으로 예외 대신 값을 낸다. */
    private static String outcome(Callable<?> call) {
        try {
            call.call();
            return "ok";
        } catch (DomainException e) {
            return e.errorCode().code();
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private static long count(String sql) throws SQLException {
        try (Connection c = unpooledConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private UUID routeOf(UUID orderId) {
        return tx().execute(status -> (UUID) entityManager.createNativeQuery("""
                SELECT s.route_id FROM route_stops s JOIN route_stop_orders o ON o.stop_id = s.id WHERE o.order_id = ?
                """).setParameter(1, orderId).getSingleResult());
    }

    private String statusOfStopHolding(UUID orderId) {
        return tx().execute(status -> (String) entityManager.createNativeQuery("""
                SELECT s.status FROM route_stops s JOIN route_stop_orders o ON o.stop_id = s.id WHERE o.order_id = ?
                """).setParameter(1, orderId).getSingleResult());
    }

    @SuppressWarnings("unchecked")
    private List<UUID> ordersOfStop(UUID stopId) {
        return tx().execute(status -> (List<UUID>) entityManager.createNativeQuery(
                        "SELECT order_id FROM route_stop_orders WHERE stop_id = ? ORDER BY order_id")
                .setParameter(1, stopId).getResultList());
    }

    /** 그 라우트의 가장 높은 개정이 싣는 주문들. */
    private List<UUID> publishedOrders(UUID routeId) {
        String payload = tx().execute(status -> (String) entityManager.createNativeQuery("""
                SELECT payload::text FROM outbox_events
                 WHERE topic = 'dawnline.route.assigned.v1' AND aggregate_id = ?
                 ORDER BY (payload->>'revision')::int DESC LIMIT 1
                """).setParameter(1, routeId).getSingleResult());
        List<UUID> orders = new ArrayList<>();
        JSON.readTree(payload).get("stops").forEach(stop ->
                stop.get("orderIds").forEach(id -> orders.add(UUID.fromString(id.asString()))));
        return orders;
    }

    /** 지점마다 주문 둘 — 계획이 한 stop 으로 합친다({@code DispatchAdminIT} 의 짝 픽스처와 같다). */
    private void seedCandidates(UUID waveId, int count) {
        Instant now = PlanningClock.PLAN_AT.truncatedTo(ChronoUnit.MICROS);
        TimeWindow window = new TimeWindow(now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(5)));
        tx().executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                int point = i / 2;
                candidates.insertIfAbsent(DispatchCandidate.load(Ids.newId(), waveId, CAMP_ID, null,
                        GeoPoint.of(CAMP.lat() + 0.004d * (point % 8 + 1) + 0.00002d * (i % 2),
                                CAMP.lng() + 0.005d * (point / 8 + 1)),
                        20_000, 40_000, false, false, window, 60, false, 0, now));
            }
        });
    }
}
