package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.port.in.ManageResourcesUseCase;
import com.dawnline.dispatch.application.port.in.ResourceViews;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.messaging.contract.EventContracts;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 계획이 읽은 차량이 계획 중에 비활성화되면 그 차량의 라우트는 발행되지 않는다 (ADR-067 재검토 지점 6 · 7-0 A33).
 *
 * <p>계획은 시작할 때 차량을 읽고 계산은 트랜잭션 밖이다(ADR-064). 그 사이의 비활성화는 409 가 막지 못한다 — 그 차량에는 아직
 * stop 이 없다. 타이밍을 락으로 고정한다: 테스트의 연결이 {@code route_plans} 를 {@code EXCLUSIVE} 모드로 쥐면 {@code SELECT} 는
 * 지나가므로 계획은 읽기와 계산을 끝내고, <em>쓰기 트랜잭션의 첫 INSERT</em>({@code route_plans})에서 멈춘다. 그때 차량을 비활성화한다
 * — {@code PlanCrashIT} 와 같은 방법이다.
 *
 * <p>픽스처는 만들고 지운다(CLAUDE.md) — 시드 캠프의 차량을 고치지 않고 이 테스트의 캠프와 차 두 대를 만든다. 냉장 주문은 냉장 차에만
 * 실리므로(하드 룰) 계획이 어느 차를 쓸지 테스트가 안다.
 */
@SpringBootTest(classes = DispatchApplication.class)
@Import(PlanningClock.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("PlanDeactivationRaceIT — 계획 중 비활성화된 차량은 발행되지 않는다")
class PlanDeactivationRaceIT extends DispatchIntegrationTestBase {

    /**
     * 릴레이를 끈다 — 이 클래스는 발행을 보지 않는다.
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void relayOff(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    private static final String WAVE_CLOSED = "dawnline.wave.closed.v1";
    private static final EventContracts CONTRACTS = EventContracts.load();

    /** 차량 제원과 근무창의 본 — 시드의 첫 캠프(PlanCrashIT 가 계획하는 캠프)의 차량에서 복사한다. */
    private static final UUID SEED_CAMP_ID = UUID.fromString("01a06edd-6c00-7000-8001-000000000001");
    private static final GeoPoint CAMP = GeoPoint.of(37.640000, 127.030000);

    /** 쓰기 트랜잭션의 첫 INSERT 에서 멈춘 계획의 백엔드. */
    private static final String BLOCKED_PLAN_SQL = """
            SELECT pid FROM pg_stat_activity
             WHERE datname = current_database() AND pid <> pg_backend_pid()
               AND wait_event_type = 'Lock' AND xact_start IS NOT NULL
               AND query ILIKE 'insert into route_plans%'
            """;

    private static KafkaProducer<String, String> producer;

    static {
        createTopics(WAVE_CLOSED);
    }

    @Autowired
    private DispatchCandidateRepository candidates;

    @Autowired
    private ManageResourcesUseCase resources;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final UUID campId = Ids.newId();

    @BeforeAll
    static void connect() {
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()));
    }

    @AfterAll
    static void disconnect() {
        producer.close();
    }

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
    void 계획_중에_비활성화된_차량의_라우트는_발행되지_않고_그_주문은_미배정이다() throws Exception {
        UUID cold = fixtureVehicle(true);
        UUID warm = fixtureVehicle(false);
        UUID waveId = Ids.newId();
        List<UUID> coldOrders = seedCandidates(waveId, 3, true);
        List<UUID> warmOrders = seedCandidates(waveId, 3, false);

        try (Connection locker = dataSource.getConnection()) {
            locker.setAutoCommit(false);
            try (Statement s = locker.createStatement()) {
                s.execute("LOCK TABLE route_plans IN EXCLUSIVE MODE");
            }
            producer.send(new ProducerRecord<>(WAVE_CLOSED, campId.toString(), waveClosed(Ids.newId(), waveId))).get();

            // 전제 — 계획이 차량을 읽고 계산을 끝낸 뒤 쓰기에서 멈췄다. 이것이 없으면 아래 비활성화가 읽기 앞에 들어가
            // 「다음 계획부터 빠진다」(ADR-067 결정 5)를 보는 테스트가 된다.
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                    .until(this::blockedPlan, Optional::isPresent);

            ResourceViews.VehicleView deactivated = resources.deactivateVehicle(cold);
            assertThat(deactivated.active()).as("전제 — 409 가 막지 못한다: 그 차량에는 아직 stop 이 없다").isFalse();

            locker.commit();
        }

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .until(() -> planStatus(waveId), status -> status.isPresent() && !status.get().equals("PLANNING"));

        assertThat(count("""
                SELECT count(*) FROM routes r JOIN route_plans p ON p.id = r.plan_id
                 WHERE p.wave_id = ? AND r.vehicle_id = ?""", waveId, cold))
                .as("비활성 차량의 라우트가 발행됐다 — 기사가 없는 차에 주문이 실린다").isZero();
        assertThat(candidateStatuses(coldOrders)).as("그 차에 실렸던 냉장 주문은 미배정이다").containsOnly("UNASSIGNED");
        assertThat(count("""
                SELECT count(*) FROM routes r JOIN route_plans p ON p.id = r.plan_id
                 WHERE p.wave_id = ? AND r.vehicle_id = ?""", waveId, warm))
                .as("살아 있는 차의 라우트는 그대로 나간다").isOne();
        assertThat(candidateStatuses(warmOrders)).containsOnly("PLANNED");
    }

    private UUID fixtureVehicle(boolean isCold) throws SQLException {
        UUID id = Ids.newId();
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement("""
                INSERT INTO vehicles (id, camp_id, code, type, max_weight_g, max_volume_cm3, is_cold, allows_hazmat,
                                      fixed_cost_krw, cost_per_km_krw, cost_per_min_krw, shift_start, shift_end, active, source)
                SELECT ?, ?, ?, type, max_weight_g, max_volume_cm3, is_cold, allows_hazmat,
                       fixed_cost_krw, cost_per_km_krw, cost_per_min_krw, shift_start, shift_end, TRUE, 'operator'
                  FROM vehicles WHERE camp_id = ? AND is_cold = ? AND active ORDER BY code LIMIT 1
                """)) {
            s.setObject(1, id);
            s.setObject(2, campId);
            s.setString(3, "A33-" + id.toString().substring(28));
            s.setObject(4, SEED_CAMP_ID);
            s.setBoolean(5, isCold);
            assertThat(s.executeUpdate()).as("전제 — 시드 캠프에 복사할 %s 차량이 있다", isCold ? "냉장" : "상온").isOne();
        }
        return id;
    }

    /** {@code PlanCrashIT.seedCandidates} 와 같다 — 약속 창의 기준은 {@link PlanningClock#PLAN_AT}. */
    private List<UUID> seedCandidates(UUID waveId, int count, boolean requiresCold) {
        Instant now = PlanningClock.PLAN_AT.truncatedTo(ChronoUnit.MICROS);
        TimeWindow window = new TimeWindow(now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(5)));
        List<UUID> orders = new ArrayList<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                UUID orderId = Ids.newId();
                double offset = (requiresCold ? 0.010d : -0.010d) + 0.002d * i;
                candidates.insertIfAbsent(DispatchCandidate.load(orderId, waveId, campId, null,
                        GeoPoint.of(CAMP.lat() + offset, CAMP.lng() + offset), 1_000, 2_000, requiresCold, false,
                        window, 60, false, 0, now));
                orders.add(orderId);
            }
        });
        return orders;
    }

    private Optional<Integer> blockedPlan() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(BLOCKED_PLAN_SQL)) {
            return rs.next() ? Optional.of(rs.getInt(1)) : Optional.empty();
        }
    }

    private Optional<String> planStatus(UUID waveId) throws SQLException {
        try (Connection c = dataSource.getConnection();
                PreparedStatement s = c.prepareStatement("SELECT status FROM route_plans WHERE wave_id = ?")) {
            s.setObject(1, waveId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    private List<String> candidateStatuses(List<UUID> orderIds) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT status FROM dispatch_candidates WHERE order_id = ANY (?)")) {
            s.setArray(1, c.createArrayOf("uuid", orderIds.toArray()));
            List<String> statuses = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    statuses.add(rs.getString(1));
                }
            }
            assertThat(statuses).as("전제 — 후보가 다 있다").hasSameSizeAs(orderIds);
            return statuses;
        }
    }

    private long count(String sql, UUID first, UUID second) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setObject(1, first);
            s.setObject(2, second);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** 계약 예시를 봉투째 쓰고 id 셋과 캠프만 바꾼다(불변규칙 8). */
    private String waveClosed(UUID eventId, UUID waveId) {
        String example;
        try {
            example = Files.readString(CONTRACTS.contractsDirectory().resolve("examples").resolve("wave.closed.v1.example.json"),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return example
                .replace("01a04e08-ee04-70ab-8398-3727f51db2f7", eventId.toString())
                .replace("01a04dad-80da-7521-9af9-6d6c3fd38228", waveId.toString())
                .replace("01a04dad-80da-704e-b002-b9fbfbfdbe93", campId.toString());
    }
}
