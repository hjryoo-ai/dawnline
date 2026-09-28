package com.dawnline.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dawnline.common.Ids;
import com.dawnline.tracking.application.ContendedScanRetry;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 스캔이 다른 쓰기와 겹치면 다시 하고, 세 번 뒤에는 409 {@code shipment-contended} 다 (DESIGN.md §5.4).
 *
 * <h2>겹침을 결정적으로 만드는 방법</h2>
 * 첫 {@code peak-day} 의 500 두 건은 동시성의 우연이었다 — 그대로 흉내 내면 이 테스트도 우연에 기댄다. 그래서 겹침이 일어나는
 * <strong>정확한 자리</strong>에 끼어든다: 스캔의 편차 전파가 라우트를 다시 읽는 순간({@link ShipmentRepository#findByRouteFrom}).
 * 그 직전에 <em>다른 연결</em>이 같은 라우트 행들의 {@code version} 을 올리고 커밋한다 — 스캔이 배송을 <em>읽은 뒤</em> 라우트 행을
 * 잡기까지 기다리는 사이에 개정 반영이 커밋한 것과 같은 모양이다(ADR-070 결정 1 — 라우트는 배송이 말하므로 읽기가 먼저다). 그러면 질의의 자동 flush 가 찍은 배송의 갱신을 {@code WHERE version = ?} 로 내보내다 0 행을 받는다: 운영과 같은
 * JPA 의 실패가 운영과 같은 자리에서 난다. 끼어드는 횟수가 시나리오다 — 1 이면 두 번째 시도가 이기고, 3 이면 셋 다 진다.
 *
 * <p>픽스처는 {@code route.assigned} 를 반영하는 유스케이스로 만들고, 만든 행을 {@code @AfterEach} 에서 지운다({@link ScanApiIT} 와 같다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("스캔 경합 — 다시 하고, 세 번 뒤에는 409")
class ScanContentionIT extends TrackingIntegrationTestBase {

    private static final UUID CAMP = UUID.randomUUID();

    private static final String SCAN_PATH = "/api/v1/routes/%s/stops/%d/events";

    /** 남은 끼어들기 횟수. 편차 전파가 라우트를 읽을 때마다 하나씩 쓴다. */
    static final AtomicInteger INTERFERENCES = new AtomicInteger();

    /** 실제로 끼어든 횟수 — 전제 어설션이 본다. */
    static final AtomicInteger INTERFERED = new AtomicInteger();

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

    /** 공유 자원은 이 IT 가 자기 자리에서 끈다 (CLAUDE.md). */
    @DynamicPropertySource
    static void 공유_자원을_끈다(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    /**
     * 배송 저장소를 감싸 편차 전파의 재조회 직전에 끼어든다. 끼어들기는 <strong>새 트랜잭션</strong>(다른 연결)이고 커밋한다 —
     * 스캔의 트랜잭션은 아직 행을 잠그지 않았다(갱신은 세션에만 있다).
     */
    @TestConfiguration
    static class Interference {

        @Bean
        static BeanPostProcessor interfereOnRouteRead(ObjectProvider<JdbcTemplate> jdbc,
                ObjectProvider<PlatformTransactionManager> transactionManager) {
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
                            if (INTERFERENCES.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                                TransactionTemplate other = new TransactionTemplate(transactionManager.getObject());
                                other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                                other.executeWithoutResult(status -> jdbc.getObject().update(
                                        "UPDATE shipments SET version = version + 1 WHERE route_id = ?", routeId));
                                INTERFERED.incrementAndGet();
                            }
                            return delegate.findByRouteFrom(routeId, fromSeq);
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
        // ScanApiIT 와 같은 이유 — 오늘의 shipment_events 파티션은 쓰는 쪽이 자기 자리에서 만든다(ensure 는 멱등이다).
        partitions.ensure(LocalDate.now(ZoneOffset.UTC).minusDays(1), 3);
        transactions = new TransactionTemplate(transactionManager);
        arrival = clock.instant().plus(Duration.ofMinutes(30));
        departure = clock.instant().plus(Duration.ofMinutes(5));
        promisedEnd = clock.instant().plus(Duration.ofHours(3));
        INTERFERENCES.set(0);
        INTERFERED.set(0);
    }

    @AfterEach
    void 만든_행을_지운다() {
        INTERFERENCES.set(0);
        createdOrders.forEach(orderId -> {
            jdbc.update("DELETE FROM shipment_events WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM shipments WHERE order_id = ?", orderId);
        });
        createdRoutes.forEach(routeId -> jdbc.update("DELETE FROM route_revisions WHERE route_id = ?", routeId));
        createdOrders.clear();
        createdRoutes.clear();
    }

    @Test
    void 한_번_겹치면_새_트랜잭션으로_다시_해서_적용된다() throws Exception {
        UUID route = newRoute();
        UUID first = newOrder();
        UUID next = newOrder();
        assign(route, stop(1, first), stop(2, next));
        INTERFERENCES.set(1);

        mockMvc.perform(arrived(route, 1, first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[0].outcome").value("APPLIED"));

        assertThat(INTERFERED.get()).as("전제 — 첫 시도의 편차 전파에 한 번 끼어들었다").isEqualTo(1);
        assertThat(statusOf(first)).isEqualTo("ARRIVED");
        assertThat(eventCount(first)).as("진 시도는 트랜잭션째 되돌아갔다 — 사건은 이긴 시도의 하나뿐이다").isEqualTo(1);
    }

    @Test
    void 세_번_겹치면_409_shipment_contended_이고_아무것도_바뀌지_않는다() throws Exception {
        UUID route = newRoute();
        UUID first = newOrder();
        UUID next = newOrder();
        assign(route, stop(1, first), stop(2, next));
        INTERFERENCES.set(ContendedScanRetry.ATTEMPTS);

        mockMvc.perform(arrived(route, 1, first))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("shipment-contended"))
                .andExpect(jsonPath("$.attempts").value(ContendedScanRetry.ATTEMPTS))
                .andExpect(header().string("Retry-After", "1"));

        assertThat(INTERFERED.get()).as("전제 — 시도마다 한 번씩 끼어들었다").isEqualTo(ContendedScanRetry.ATTEMPTS);
        assertThat(statusOf(first)).as("적용되지 않았다 — 500 과 달리 이 사실을 응답이 말한다").isEqualTo("SCHEDULED");
        assertThat(eventCount(first)).isZero();
    }

    @Test
    void 충돌_409_를_받고_같은_요청을_다시_보내면_적용된다() throws Exception {
        // 계약의 문장(「같은 요청을 그대로 다시 보내면 된다」)을 그대로 한다.
        UUID route = newRoute();
        UUID first = newOrder();
        UUID next = newOrder();
        assign(route, stop(1, first), stop(2, next));
        INTERFERENCES.set(ContendedScanRetry.ATTEMPTS);
        mockMvc.perform(arrived(route, 1, first)).andExpect(status().isConflict());

        mockMvc.perform(arrived(route, 1, first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[0].outcome").value("APPLIED"));

        assertThat(statusOf(first)).isEqualTo("ARRIVED");
    }

    private org.springframework.test.web.servlet.RequestBuilder arrived(UUID routeId, int seq, UUID orderId) {
        return post(SCAN_PATH.formatted(routeId, seq))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"ARRIVED\",\"occurredAt\":\"%s\",\"orderIds\":[\"%s\"]}"
                        .formatted(clock.instant(), orderId));
    }

    private void assign(UUID routeId, AssignedStop... stops) {
        transactions.executeWithoutResult(status ->
                applyRouteAssignment.apply(new RouteAssignment(routeId, 1, CAMP, departure, List.of(stops))));
    }

    private AssignedStop stop(int seq, UUID orderId) {
        return new AssignedStop(seq, List.of(orderId), Set.of(), arrival.plus(Duration.ofMinutes(seq)), promisedEnd);
    }

    private String statusOf(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM shipments WHERE order_id = ?", String.class, orderId);
    }

    private int eventCount(UUID orderId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM shipment_events WHERE order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
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
