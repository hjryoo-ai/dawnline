package com.dawnline.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.dawnline.common.Ids;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.AssignedStop;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.RouteAssignment;
import com.dawnline.tracking.application.port.out.ShipmentRepository;
import com.dawnline.tracking.domain.Shipment;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 스캔과 개정 반영이 한 라우트의 배송들을 동시에 고친다 — 교착하지 않는다
 * ([ADR-070](docs/adr/ADR-070-tracking-writes-lock-the-route-first.md) 결정 1, 7-4 리포트 §3.2).
 *
 * <h2>짝</h2>
 * 스캔은 배송을 <strong>stop 순서</strong>로 잡는다 — 찍은 배송은 편차 계산의 질의 직전 자동 flush 에서, 나머지는 커밋에서. 개정 반영은
 * <strong>주문 id 순서</strong>로 잡는다({@code findAll … ORDER BY order_id}). 픽스처는 주문 id 가 방문 순서의 반대가 되게 만든다 — 그러면
 * 스캔이 첫 stop 을 쥔 채 개정이 뒤에서부터 잡아 들어오고, 정정 전 코드는 여기서 {@code deadlock detected} 였다(근거: 관측(재현됨)).
 *
 * <h2>끼어드는 자리</h2>
 * 스캔이 라우트를 읽은 직후({@link ShipmentRepository#findByRouteFrom} — 찍은 배송이 이미 flush 된 뒤)에 멈추고, 그동안 다른 스레드가
 * 같은 라우트의 개정을 반영한다. 개정이 <em>잠금을 기다리는 것</em>을 {@code pg_stat_activity} 로 확인한 뒤 스캔을 풀어 준다 — 우연에 기대지
 * 않는다. 정정 뒤에는 개정이 라우트 행(claim)에서 기다린다: 쓰기 계층의 부모에서 줄을 서는 것이 이 IT 가 보는 모양이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("스캔 대 개정 반영")
class ScanRevisionRaceIT extends TrackingIntegrationTestBase {

    private static final UUID CAMP = UUID.randomUUID();

    private static final String SCAN_PATH = "/api/v1/routes/%s/stops/%d/events";

    static final AtomicBoolean ARMED = new AtomicBoolean();
    static volatile CountDownLatch scanHolds = new CountDownLatch(1);
    static volatile CountDownLatch release = new CountDownLatch(1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplyRouteAssignmentUseCase applyRouteAssignment;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @Autowired
    private com.dawnline.tracking.application.port.out.EventPartitions partitions;

    private TransactionTemplate transactions;
    private Instant arrival;
    private Instant departure;
    private Instant promisedEnd;
    private final Set<UUID> createdOrders = new LinkedHashSet<>();
    private final Set<UUID> createdRoutes = new LinkedHashSet<>();

    @DynamicPropertySource
    static void 공유_자원을_끈다(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @TestConfiguration
    static class Gate {

        @Bean
        static BeanPostProcessor holdAfterRouteRead() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof ShipmentRepository delegate)) {
                        return bean;
                    }
                    return new ShipmentRepository() {
                        @Override
                        public List<Shipment> findAll(Collection<UUID> orderIds) {
                            return delegate.findAll(orderIds);
                        }

                        @Override
                        public List<Shipment> findByRouteFrom(UUID routeId, int fromSeq) {
                            List<Shipment> found = delegate.findByRouteFrom(routeId, fromSeq);
                            if (ARMED.compareAndSet(true, false)) {
                                scanHolds.countDown();
                                try {
                                    release.await(30, TimeUnit.SECONDS);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            return found;
                        }

                        @Override
                        public void insert(Shipment shipment) {
                            delegate.insert(shipment);
                        }

                        @Override
                        public void update(Shipment shipment) {
                            delegate.update(shipment);
                        }
                    };
                }
            };
        }
    }

    @BeforeEach
    void setUp() {
        partitions.ensure(LocalDate.now(ZoneOffset.UTC).minusDays(1), 3);
        transactions = new TransactionTemplate(transactionManager);
        arrival = clock.instant().plus(Duration.ofMinutes(30));
        departure = clock.instant().plus(Duration.ofMinutes(5));
        promisedEnd = clock.instant().plus(Duration.ofHours(3));
        ARMED.set(false);
        scanHolds = new CountDownLatch(1);
        release = new CountDownLatch(1);
    }

    @AfterEach
    void 만든_행을_지운다() {
        ARMED.set(false);
        release.countDown();
        createdOrders.forEach(orderId -> {
            jdbc.update("DELETE FROM shipment_events WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM shipments WHERE order_id = ?", orderId);
        });
        createdRoutes.forEach(routeId -> jdbc.update("DELETE FROM route_revisions WHERE route_id = ?", routeId));
        createdOrders.clear();
        createdRoutes.clear();
    }

    @Test
    void 스캔이_앞_stop_을_쓴_채_개정이_뒤에서부터_들어와도_교착하지_않는다() throws Exception {
        UUID route = newRoute();
        // 주문 id 순서가 방문 순서의 반대다 — 개정 반영은 주문 id 순으로 읽는다(findAll 의 ORDER BY).
        UUID third = newOrder();
        UUID second = newOrder();
        UUID first = newOrder();
        assign(route, 1, Duration.ZERO, first, second, third);
        long deadlocksBefore = deadlocks();

        ARMED.set(true);
        CompletableFuture<Integer> scan = CompletableFuture.supplyAsync(() -> {
            try {
                return mockMvc.perform(arrived(route, 1, first)).andReturn().getResponse().getStatus();
            } catch (Exception failed) {
                throw new IllegalStateException(failed);
            }
        });
        assertThat(scanHolds.await(20, TimeUnit.SECONDS)).as("전제 — 스캔이 첫 stop 을 쓰고 멈췄다").isTrue();

        CompletableFuture<Void> revise = CompletableFuture.runAsync(
                () -> assign(route, 2, Duration.ofMinutes(2), first, second, third));
        String waiting = awaitLockWaiter();
        assertThat(waiting).as("개정은 라우트 행(claim)에서 기다린다 — 배송이 아니라 부모에서 줄을 선다").contains("route_revisions");
        release.countDown();

        int scanStatus = scan.get(30, TimeUnit.SECONDS);
        Throwable reviseFailure = null;
        try {
            revise.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException failed) {
            reviseFailure = failed.getCause();
        }
        assertThat(deadlocks() - deadlocksBefore).as("교착").isZero();
        assertThat(scanStatus).isEqualTo(200);
        assertThat(reviseFailure).as("개정은 스캔 뒤에 적용된다").isNull();
        assertThat(jdbc.queryForObject("SELECT revision FROM route_revisions WHERE route_id = ?", Integer.class, route))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM shipments WHERE order_id = ?", String.class, first))
                .as("스캔도 남는다 — 개정은 진행 중 배송의 상태를 되돌리지 않는다").isEqualTo("ARRIVED");
    }

    /** @return 잠금을 기다리는 문장 */
    private String awaitLockWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            List<String> waiting = jdbc.queryForList(
                    "SELECT query FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
                    String.class);
            if (!waiting.isEmpty()) {
                return waiting.getFirst();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("전제 — 개정 반영이 잠금을 기다리지 않았다");
    }

    private long deadlocks() {
        Long count = jdbc.queryForObject(
                "SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()", Long.class);
        return count == null ? 0 : count;
    }

    private org.springframework.test.web.servlet.RequestBuilder arrived(UUID routeId, int seq, UUID orderId) {
        return post(SCAN_PATH.formatted(routeId, seq))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"ARRIVED\",\"occurredAt\":\"%s\",\"orderIds\":[\"%s\"]}"
                        .formatted(clock.instant(), orderId));
    }

    private void assign(UUID routeId, int revision, Duration shift, UUID... orders) {
        List<AssignedStop> stops = new java.util.ArrayList<>();
        for (int i = 0; i < orders.length; i++) {
            stops.add(new AssignedStop(i + 1, List.of(orders[i]), Set.of(),
                    arrival.plus(Duration.ofMinutes(10L * (i + 1))).plus(shift), promisedEnd));
        }
        transactions.executeWithoutResult(status ->
                applyRouteAssignment.apply(new RouteAssignment(routeId, revision, CAMP, departure, stops)));
    }

    private UUID newOrder() {
        UUID orderId = Ids.newId();
        createdOrders.add(orderId);
        return orderId;
    }

    private UUID newRoute() {
        UUID routeId = Ids.newId();
        createdRoutes.add(routeId);
        return routeId;
    }
}
