package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.port.in.RunPlanCommand;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.domain.DispatchCandidate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
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
 * 한 차량은 한 시각에 한 곳에 있다 — 두 번째 계획은 첫 번째가 잡은 차량 시간을 읽고, 파티션을 지나지 않는 두 계획은 발행에서
 * 직렬화된다 (ADR-075 결정 3 · 4, V10 의 IT 판).
 *
 * <p>7-4 의 다섯 실행에서 같은 차량의 DAWN · NEXT_DAY 라우트가 계획 시각에서 13–43쌍 겹쳤다(근거: 관측). 여기서는 캠프에 차 한 대를
 * 두고 같은 캠프의 웨이브 둘을 계획한다 — 첫 테스트는 차례로(리스너 경로의 모양: {@code wave.closed} 의 키가 캠프라 같은 캠프의
 * 계획은 한 스레드에서 차례로 돈다), 둘째는 동시에(운영자 재실행의 모양: 웹 스레드가 같은 유스케이스를 부른다). 두 경로 모두
 * 유스케이스를 직접 부른다 — 이 테스트가 보는 것은 소비가 아니라 계획의 읽기와 발행이다.
 *
 * <p>픽스처는 만들고 지운다(CLAUDE.md) — 이 테스트의 캠프와 차 한 대.
 */
@SpringBootTest(classes = DispatchApplication.class)
@Import(PlanningClock.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("PlanOccupancyIT — 한 차량의 라우트 둘은 계획 시각에서 겹치지 않는다")
class PlanOccupancyIT extends DispatchIntegrationTestBase {

    /**
     * 릴레이를 끈다 — 이 클래스는 발행을 보지 않는다.
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void relayOff(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    /** 차량 제원과 근무창의 본 — 시드의 첫 캠프의 주간 근무 상온 차량({@code PlanDeactivationRaceIT} 와 같다). */
    private static final UUID SEED_CAMP_ID = UUID.fromString("01a06edd-6c00-7000-8001-000000000001");
    private static final GeoPoint CAMP = GeoPoint.of(37.640000, 127.030000);

    /** V10 의 식을 이 테스트의 차량 하나로 좁힌 것 — stop 이 전부 취소된 라우트는 차를 쓰지 않으므로 뺀다. */
    private static final String OVERLAPS = """
            WITH span AS (
              SELECT r.id, r.planned_departure AS s, r.planned_departure + make_interval(secs => r.duration_s) AS e
                FROM routes r
               WHERE r.vehicle_id = ? AND r.planned_departure IS NOT NULL
                 AND EXISTS (SELECT 1 FROM route_stops s WHERE s.route_id = r.id AND s.status <> 'CANCELLED'))
            SELECT count(*) FROM span a JOIN span b ON a.id < b.id AND a.s < b.e AND b.s < a.e
            """;

    private static final String BLOCKED_SQL = """
            SELECT count(*) FROM pg_stat_activity
             WHERE datname = current_database() AND pid <> pg_backend_pid()
               AND wait_event_type = 'Lock' AND xact_start IS NOT NULL
               AND ltrim(query) ILIKE 'insert into route_plans%'
            """;

    @Autowired
    private RunPlanUseCase runPlan;

    @Autowired
    private DispatchCandidateRepository candidates;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final UUID campId = Ids.newId();

    @BeforeEach
    @AfterEach
    void clean() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String table : new String[] {"plan_explanations", "route_stop_orders", "route_stops", "routes",
                    "route_plans", "dispatch_candidates", "processed_events", "outbox_events"}) {
                s.executeUpdate("DELETE FROM " + table);
            }
            s.executeUpdate("DELETE FROM vehicles WHERE camp_id = '" + campId + "'");
        }
    }

    @Test
    void 두_번째_계획은_첫_계획이_잡은_차량_시간_뒤에_출발한다() throws SQLException {
        UUID vehicle = fixtureVehicle();
        UUID first = Ids.newId();
        UUID second = Ids.newId();
        seedCandidates(first, 3, 0.010d);
        seedCandidates(second, 3, -0.010d);

        assertThat(runPlan.run(RunPlanCommand.of(first, campId, CAMP, null))).isEqualTo(RunPlanUseCase.Outcome.PUBLISHED);
        assertThat(runPlan.run(RunPlanCommand.of(second, campId, CAMP, null)))
                .as("전제 — 둘째 웨이브도 그 차 한 대로 발행된다(첫 라우트가 돌아온 뒤에도 약속창 안이다)")
                .isEqualTo(RunPlanUseCase.Outcome.PUBLISHED);

        assertThat(count("SELECT count(*) FROM routes WHERE vehicle_id = ?", vehicle)).as("전제 — 두 웨이브가 같은 차를 썼다")
                .isEqualTo(2L);
        assertThat(count(OVERLAPS, vehicle))
                .as("한 차량의 라우트 둘이 계획 시각에서 겹친다 — 둘째 계획이 첫째가 잡은 차량 시간을 몰랐다(7-4 의 13–43쌍)")
                .isZero();
    }

    @Test
    void 파티션을_지나지_않는_두_계획은_발행에서_직렬화되고_진_쪽은_미배정이다() throws Exception {
        UUID vehicle = fixtureVehicle();
        UUID first = Ids.newId();
        UUID second = Ids.newId();
        List<UUID> firstOrders = seedCandidates(first, 3, 0.010d);
        List<UUID> secondOrders = seedCandidates(second, 3, -0.010d);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<RunPlanUseCase.Outcome>> outcomes = new ArrayList<>();
            try (Connection locker = dataSource.getConnection()) {
                locker.setAutoCommit(false);
                try (Statement s = locker.createStatement()) {
                    s.execute("LOCK TABLE route_plans IN EXCLUSIVE MODE");
                }
                outcomes.add(pool.submit(() -> runPlan.run(RunPlanCommand.of(first, campId, CAMP, null))));
                outcomes.add(pool.submit(() -> runPlan.run(RunPlanCommand.of(second, campId, CAMP, null))));

                // 전제 — 두 계획 모두 차량을 읽고 계산을 끝낸 뒤 쓰기의 첫 INSERT 에서 멈췄다. 둘 다 「차가 비어 있다」를 읽었다.
                // 이것이 없으면 한쪽이 다른 쪽의 커밋 뒤에 읽어 결정 3 의 읽기만으로 풀리는 테스트가 된다.
                await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                        .until(this::blockedPlans, blocked -> blocked == 2L);
                locker.commit();
            }
            List<RunPlanUseCase.Outcome> results = new ArrayList<>();
            for (Future<RunPlanUseCase.Outcome> outcome : outcomes) {
                results.add(outcome.get(60, TimeUnit.SECONDS));
            }

            assertThat(count(OVERLAPS, vehicle))
                    .as("동시에 계산한 두 계획이 같은 차의 같은 시간을 발행했다 — 파티션 논거는 운영자 재실행 경로에서 거짓이다")
                    .isZero();
            assertThat(results).as("하나는 발행되고, 진 쪽은 라우트가 전부 빠져 실패한다(차가 한 대다)")
                    .containsExactlyInAnyOrder(RunPlanUseCase.Outcome.PUBLISHED, RunPlanUseCase.Outcome.FAILED);
            List<UUID> loser = results.get(0) == RunPlanUseCase.Outcome.FAILED ? firstOrders : secondOrders;
            assertThat(count("SELECT count(*) FROM route_stop_orders WHERE order_id = ANY (?)", loser))
                    .as("진 계획의 주문은 어느 라우트에도 없다 — 재실행이 한 번 더 필요하다(ADR-075 결정 4 의 대가)").isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    private UUID fixtureVehicle() throws SQLException {
        UUID id = Ids.newId();
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement("""
                INSERT INTO vehicles (id, camp_id, code, type, max_weight_g, max_volume_cm3, is_cold, allows_hazmat,
                                      fixed_cost_krw, cost_per_km_krw, cost_per_min_krw, shift_start, shift_end, active, source)
                SELECT ?, ?, ?, type, max_weight_g, max_volume_cm3, is_cold, allows_hazmat,
                       fixed_cost_krw, cost_per_km_krw, cost_per_min_krw, shift_start, shift_end, TRUE, 'operator'
                  FROM vehicles WHERE camp_id = ? AND NOT is_cold AND active AND shift_start < shift_end
                 ORDER BY code LIMIT 1
                """)) {
            s.setObject(1, id);
            s.setObject(2, campId);
            s.setString(3, "OCC-" + id.toString().substring(28));
            s.setObject(4, SEED_CAMP_ID);
            assertThat(s.executeUpdate()).as("전제 — 시드 캠프에 복사할 주간 상온 차량이 있다").isOne();
        }
        return id;
    }

    /** 약속창은 계획 시각 +1h ~ +5h — 두 웨이브가 같은 창이고, 첫 라우트가 돌아온 뒤에도 둘째가 창 안에 든다. */
    private List<UUID> seedCandidates(UUID waveId, int count, double offset) {
        Instant now = PlanningClock.PLAN_AT.truncatedTo(ChronoUnit.MICROS);
        TimeWindow window = new TimeWindow(now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(5)));
        List<UUID> orders = new ArrayList<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                UUID orderId = Ids.newId();
                double at = offset + 0.002d * i;
                candidates.insertIfAbsent(DispatchCandidate.load(orderId, waveId, campId, null,
                        GeoPoint.of(CAMP.lat() + at, CAMP.lng() + at), 1_000, 2_000, false, false,
                        window, 60, false, 0, now));
                orders.add(orderId);
            }
        });
        return orders;
    }

    private long blockedPlans() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(BLOCKED_SQL)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long count(String sql, UUID vehicleId) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setObject(1, vehicleId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long count(String sql, List<UUID> ids) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setArray(1, c.createArrayOf("uuid", ids.toArray()));
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
