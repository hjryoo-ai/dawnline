package com.dawnline.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Ids;
import com.dawnline.messaging.MessagingMetrics;
import com.dawnline.messaging.retention.ManualClock;
import com.dawnline.tracking.adapter.out.persistence.JpaShipmentRepository;
import com.dawnline.tracking.application.TrackingRetentionCleaner;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.AssignedStop;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.Outcome;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.RouteAssignment;
import com.dawnline.tracking.domain.Shipment;
import com.dawnline.tracking.domain.ShipmentStatus;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code shipments} · {@code route_revisions} 보존 — 실제 PostgreSQL 에서 (ADR-058, DESIGN.md §5.4 「보존」).
 *
 * <p>무엇을 지우는가(종결만 · 가드 · 상한)는 SQL 의 일이라 여기서 본다. 정리기의 순서·임계·실패의 모양은
 * {@code TrackingRetentionCleanerTest} 가 본다.
 *
 * <h2>픽스처는 자기 행만 만든다</h2>
 * 나이를 주입된 시계에서 뽑아 30일·90일·365일 <em>전</em>으로 둔 행을 만들고, 테스트가 끝나면 지운다. 정리기는
 * 표 전체를 보지만 그만큼 오래된 행은 이 테스트의 것뿐이다 — 다른 IT 의 행은 전부 지금 만들어진다.
 */
@SpringBootTest
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("tracking 보존 — 종결 30일 · 상한 365일 · 개정 90일 (ADR-058)")
class TrackingRetentionIT extends TrackingIntegrationTestBase {

    private static final UUID CAMP = UUID.randomUUID();

    @Autowired
    private TrackingRetentionCleaner cleaner;

    @Autowired
    private ApplyRouteAssignmentUseCase applyRouteAssignment;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private Clock clock;

    private TransactionTemplate transactions;
    private Instant now;
    private final Set<UUID> createdOrders = new LinkedHashSet<>();
    private final Set<UUID> createdRoutes = new LinkedHashSet<>();

    /**
     * 이 IT 가 자기 자리에서 끄고 미룬다 (CLAUDE.md — 기반은 이 속성에 의견을 갖지 않는다). 정리는 테스트가
     * 직접 부른다 — 스케줄이 같은 순간 돌면 「무엇이 지웠나」를 가를 수 없다.
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void 공유_자원을_끈다(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
        registry.add("dawnline.tracking.partitions.initial-delay-ms", () -> "3600000");
        registry.add("dawnline.tracking.retention.cleanup-initial-delay-ms", () -> "3600000");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        now = clock.instant();
    }

    @AfterEach
    void 만든_행을_지운다() {
        createdOrders.forEach(orderId -> jdbc.update("DELETE FROM shipments WHERE order_id = ?", orderId));
        createdRoutes.forEach(routeId -> jdbc.update("DELETE FROM route_revisions WHERE route_id = ?", routeId));
        createdOrders.clear();
        createdRoutes.clear();
    }

    // --- shipments ----------------------------------------------------------

    @Test
    void 종결_배송만_30일에_지운다() {
        UUID route = newRoute();
        UUID completed = shipment(route, "COMPLETED", daysAgo(31));
        UUID failed = shipment(route, "FAILED", daysAgo(31));
        UUID cancelled = shipment(route, "CANCELLED", daysAgo(31));
        UUID stuck = shipment(route, "OUT_FOR_DELIVERY", daysAgo(31));
        UUID recent = shipment(route, "COMPLETED", daysAgo(29));

        cleaner.deleteExpired();

        assertThat(exists(completed)).isFalse();
        assertThat(exists(failed)).isFalse();
        assertThat(exists(cancelled)).isFalse();
        assertThat(exists(stuck)).as("걸린 배송은 조사 대상이다 — 30일에 지우지 않는다").isTrue();
        assertThat(exists(recent)).isTrue();
    }

    @Test
    void 상한은_비종결_배송도_지운다() {
        UUID route = newRoute();
        UUID ancient = shipment(route, "SCHEDULED", daysAgo(366));
        UUID stuck = shipment(route, "ARRIVED", daysAgo(364));

        cleaner.deleteExpired();

        assertThat(exists(ancient)).as("365일 상한 — 정리이지 정책이 아니다").isFalse();
        assertThat(exists(stuck)).isTrue();
    }

    // --- route_revisions ----------------------------------------------------

    @Test
    void 개정은_참조하는_배송이_없을_때만_90일에_지운다() {
        UUID guarded = newRoute();
        revision(guarded, 1, daysAgo(91));
        shipment(guarded, "OUT_FOR_DELIVERY", daysAgo(91));
        UUID orphan = newRoute();
        revision(orphan, 1, daysAgo(91));
        UUID young = newRoute();
        revision(young, 1, daysAgo(89));

        cleaner.deleteExpired();

        assertThat(revisionExists(guarded)).as("가드 — 배송이 남은 라우트의 개정은 번호 비교의 자리다").isTrue();
        assertThat(revisionExists(orphan)).isFalse();
        assertThat(revisionExists(young)).isTrue();
    }

    @Test
    void 배송이_지워진_라우트의_재처리된_개정은_남은_개정_행에_막힌다() {
        // ADR-058 결정 1 — shipments 30일은 DLQ 30일과 등호라 여유가 없고, 둘째 방어가 이것이다.
        UUID route = newRoute();
        UUID order = newOrder();
        RouteAssignment assignment = new RouteAssignment(route, 1, CAMP, now.plus(Duration.ofMinutes(5)),
                List.of(new AssignedStop(1, List.of(order), Set.of(), now.plus(Duration.ofMinutes(30)),
                        now.plus(Duration.ofHours(3)))));
        apply(assignment);
        age(order, route, "COMPLETED", daysAgo(31));

        cleaner.deleteExpired();
        assertThat(exists(order)).as("전제 — 종결 배송은 30일에 지워졌다").isFalse();
        assertThat(revisionExists(route)).as("전제 — 개정 행은 남았다").isTrue();

        Outcome replay = apply(assignment);

        assertThat(replay.kind()).isEqualTo(Outcome.Kind.STALE);
        assertThat(exists(order)).as("지운 배송이 되살아나지 않는다").isFalse();
    }

    // --- 나이의 칸 ----------------------------------------------------------

    @Test
    void 배송의_나이는_값이_바뀐_쓰기만_옮긴다() {
        // 칸의 매핑(@Column updatable)이 실제로 걸려 있는지를 보는 자리다 — 엔티티 단위 테스트는 필드를 보고,
        // 저장된 값은 여기서만 보인다.
        ManualClock stamps = new ManualClock(now);
        JpaShipmentRepository repository = new JpaShipmentRepository(
                SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory), stamps);
        UUID route = newRoute();
        UUID order = newOrder();
        Shipment scheduled = Shipment.scheduled(order, route, 1, now.plus(Duration.ofMinutes(30)),
                now.plus(Duration.ofHours(3)));
        transactions.executeWithoutResult(status -> repository.insert(scheduled));
        assertThat(updatedAt(order)).isEqualTo(now);

        stamps.advance(Duration.ofMinutes(5));
        transactions.executeWithoutResult(status -> repository.update(scheduled));
        assertThat(updatedAt(order)).as("바뀐 값이 없는 저장은 사건이 아니다").isEqualTo(now);

        stamps.advance(Duration.ofMinutes(5));
        Shipment departed = Shipment.restore(order, route, 1, ShipmentStatus.OUT_FOR_DELIVERY,
                scheduled.plannedArrival(), scheduled.etaAt(), scheduled.promisedEnd(), null, 0);
        transactions.executeWithoutResult(status -> repository.update(departed));
        assertThat(updatedAt(order)).isEqualTo(now.plus(Duration.ofMinutes(10)));
    }

    // --- 게이지 -------------------------------------------------------------

    @Test
    void 정리가_있는_표마다_성공_나이_게이지가_기동_때부터_있다() {
        // 없는 시계열에는 알림이 울리지 않는다(§9.1 「짝」). 이 컨텍스트에서 정리가 있는 표 전부 —
        // outbox 는 이 IT 가 껐다.
        for (String table : List.of("shipments", "route_revisions", "shipment_events", "processed_events")) {
            assertThat(meters.find(MessagingMetrics.RETENTION_LAST_SUCCESS_AGE).tag(MessagingMetrics.TAG_TABLE, table)
                    .gauge())
                    .as("table=%s", table)
                    .isNotNull();
        }
    }

    @Test
    void 끝까지_돈_정리는_두_표의_성공_나이를_되돌린다() {
        cleaner.deleteExpired();

        for (String table : List.of("shipments", "route_revisions")) {
            double age = meters.get(MessagingMetrics.RETENTION_LAST_SUCCESS_AGE)
                    .tag(MessagingMetrics.TAG_TABLE, table).gauge().value();
            assertThat(age).as("table=%s", table).isLessThan(60.0);
        }
    }

    // --- 픽스처 --------------------------------------------------------------

    private Outcome apply(RouteAssignment assignment) {
        return transactions.execute(status -> applyRouteAssignment.apply(assignment));
    }

    private Instant daysAgo(int days) {
        return now.minus(Duration.ofDays(days));
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

    private UUID shipment(UUID route, String status, Instant updatedAt) {
        UUID order = newOrder();
        Instant arrival = updatedAt.minus(Duration.ofHours(2));
        jdbc.update("""
                INSERT INTO shipments (order_id, route_id, stop_seq, status, planned_arrival, eta_at, promised_end,
                                       delivered_at, updated_at)
                VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?)
                """, order, route, status, utc(arrival), utc(arrival), utc(arrival.plus(Duration.ofHours(1))),
                "COMPLETED".equals(status) ? utc(updatedAt) : null, utc(updatedAt));
        return order;
    }

    private void age(UUID order, UUID route, String status, Instant at) {
        // 자기 픽스처 행 둘을 늙힌다 — 공유 시드가 아니라 이 테스트가 만든 행이라 반경이 그 행뿐이다.
        assertThat(jdbc.update("UPDATE shipments SET status = ?, updated_at = ? WHERE order_id = ?",
                status, utc(at), order)).isEqualTo(1);
        assertThat(jdbc.update("UPDATE route_revisions SET applied_at = ? WHERE route_id = ?", utc(at), route))
                .isEqualTo(1);
    }

    private void revision(UUID route, int revision, Instant appliedAt) {
        jdbc.update("""
                INSERT INTO route_revisions (route_id, revision, camp_id, planned_departure, applied_at)
                VALUES (?, ?, ?, ?, ?)
                """, route, revision, CAMP, utc(appliedAt), utc(appliedAt));
    }

    private boolean exists(UUID order) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM shipments WHERE order_id = ?)", Boolean.class, order));
    }

    private boolean revisionExists(UUID route) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM route_revisions WHERE route_id = ?)", Boolean.class, route));
    }

    private Instant updatedAt(UUID order) {
        return jdbc.queryForObject("SELECT updated_at FROM shipments WHERE order_id = ?", OffsetDateTime.class, order)
                .toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
