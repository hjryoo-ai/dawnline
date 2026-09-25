package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
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
 * 계획 하나는 트랜잭션 하나다 — 계획 중에 끊기면 <strong>아무것도 남지 않고</strong>, 다시 받은 {@code wave.closed} 가
 * 처음부터 끝낸다 (DESIGN.md §5.3, ADR-024 후속 정정 결정 3).
 *
 * <p>크래시를 흉내 내는 방법: 테스트의 연결이 {@code routes} 에 배타 락을 쥐어 계획을 <em>결과 쓰기</em>에서 세우고, 그
 * 계획의 백엔드를 {@code pg_terminate_backend} 로 끊는다. DB 가 보기에 프로세스가 죽은 것과 같다 — 트랜잭션이 커밋 없이
 * 사라진다. 프로세스 수준의 같은 관측은 {@code make chaos-kill} 이 한다(그쪽은 재기동 뒤 커밋되지 않은 오프셋이
 * 다시 전달하고, 여기서는 에러 핸들러가 같은 레코드를 다시 읽힌다 — 어느 쪽이든 「같은 {@code wave.closed} 를 처음부터」다).
 *
 * <p>이 테스트가 막는 것: 누가 계획을 여러 트랜잭션으로 나누면(예: {@code PLANNING} 을 먼저 커밋) 끊긴 뒤에 행이 남고, 그
 * 행을 치울 정체 회수는 이제 없다. 그 변경은 ADR 로 와야 한다.
 */
@SpringBootTest(classes = DispatchApplication.class)
@Import(PlanningClock.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("PlanCrashIT — 계획 트랜잭션이 끊기면 남는 것이 없다")
class PlanCrashIT extends DispatchIntegrationTestBase {

    /**
     * 릴레이를 끈다 — 이 클래스는 발행을 보지 않는다(outbox 행은 계획과 같은 트랜잭션이라 남는지만 본다).
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void relayOff(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    private static final String WAVE_CLOSED = "dawnline.wave.closed.v1";
    private static final EventContracts CONTRACTS = EventContracts.load();

    /** 시드의 첫 캠프 (서울 북부). 차량 20대가 여기 붙어 있다 — {@code PlanExecutionIT} 와 같다. */
    private static final UUID CAMP_ID = UUID.fromString("01a06edd-6c00-7000-8001-000000000001");
    private static final GeoPoint CAMP = GeoPoint.of(37.640000, 127.030000);

    /** 결과 쓰기에서 멈춘 계획의 백엔드 — 테스트 자신의 연결은 뺀다. */
    private static final String BLOCKED_PLAN_SQL = """
            SELECT pid FROM pg_stat_activity
             WHERE datname = current_database() AND pid <> pg_backend_pid()
               AND wait_event_type = 'Lock' AND xact_start IS NOT NULL
               AND query ILIKE 'insert into routes%'
            """;

    private static KafkaProducer<String, String> producer;

    static {
        createTopics(WAVE_CLOSED);
    }

    @Autowired
    private DispatchCandidateRepository candidates;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
    void clean() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String table : new String[] {"plan_explanations", "route_stop_orders", "route_stops", "routes",
                    "route_plans", "dispatch_candidates", "processed_events", "outbox_events"}) {
                s.executeUpdate("DELETE FROM " + table);
            }
        }
    }

    @Test
    void 결과_쓰기에서_끊긴_계획은_아무것도_남기지_않고_다시_받은_wave_closed_가_끝낸다() throws Exception {
        UUID waveId = Ids.newId();
        int seeded = seedCandidates(waveId, 5);
        UUID eventId = Ids.newId();

        try (Connection locker = dataSource.getConnection()) {
            locker.setAutoCommit(false);
            try (Statement s = locker.createStatement()) {
                s.execute("LOCK TABLE routes IN ACCESS EXCLUSIVE MODE");
            }

            producer.send(new ProducerRecord<>(WAVE_CLOSED, CAMP_ID.toString(), waveClosed(eventId, waveId))).get();

            // 전제를 먼저 말한다 — 계획이 결과 쓰기에서 트랜잭션을 연 채 멈췄다. 이것이 없으면 아래의 「남은 것 0」은
            // 계획이 시작도 안 한 상태를 본 것일 수 있다.
            int pid = await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                    .until(this::blockedPlan, Optional::isPresent).orElseThrow();

            // 크래시 — 그 백엔드를 끊는다. 트랜잭션은 커밋 없이 사라진다.
            try (Connection admin = dataSource.getConnection(); Statement s = admin.createStatement()) {
                s.execute("SELECT pg_terminate_backend(" + pid + ")");
            }
            await().atMost(Duration.ofSeconds(10)).until(() -> !backendAlive(pid));

            // 락은 아직 쥐고 있다 — 다시 받은 계획도 결과 쓰기에서 기다리므로, 지금 보이는 것은 끊긴 트랜잭션이 남긴 것뿐이다.
            assertThat(count("SELECT count(*) FROM route_plans WHERE wave_id = ?", waveId))
                    .as("끊긴 계획은 행을 남기지 않는다 — REQUESTED 도 PLANNING 도")
                    .isZero();
            assertThat(count("SELECT count(*) FROM route_plans WHERE status = 'PLANNING'", null)).isZero();
            assertThat(count("SELECT count(*) FROM processed_events WHERE event_id = ?", eventId))
                    .as("멱등 기록도 같은 트랜잭션이다 — 남으면 다시 받은 wave.closed 가 건너뛰어진다")
                    .isZero();
            assertThat(count("SELECT count(*) FROM outbox_events", null)).as("발행도 함께 사라졌다").isZero();

            locker.commit();
        }

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .until(() -> planStatus(waveId), status -> status.equals(Optional.of("PUBLISHED")));

        assertThat(count("SELECT count(*) FROM route_plans WHERE wave_id = ?", waveId)).isOne();
        assertThat(count("SELECT count(*) FROM processed_events WHERE event_id = ?", eventId)).isOne();
        assertThat(count("""
                SELECT count(*) FROM (SELECT o.order_id FROM route_stop_orders o
                                        JOIN route_stops s ON s.id = o.stop_id
                                        JOIN routes r ON r.id = s.route_id
                                        JOIN route_plans p ON p.id = r.plan_id
                                       WHERE p.wave_id = ? GROUP BY o.order_id HAVING count(*) > 1) d
                """, waveId)).as("라우트 stop 주문 중복 — 검증 표 V2").isZero();
        assertThat(count("SELECT count(*) FROM dispatch_candidates WHERE wave_id = ? AND status IN ('PLANNED', 'UNASSIGNED')",
                waveId)).as("후보 전부가 한 번의 계획 결과를 받았다").isEqualTo(seeded);
    }

    private Optional<Integer> blockedPlan() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(BLOCKED_PLAN_SQL)) {
            return rs.next() ? Optional.of(rs.getInt(1)) : Optional.empty();
        }
    }

    private boolean backendAlive(int pid) throws SQLException {
        return count("SELECT count(*) FROM pg_stat_activity WHERE pid = " + pid, null) > 0;
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

    private long count(String sql, UUID parameter) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            if (parameter != null) {
                s.setObject(1, parameter);
            }
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** 계약 예시를 봉투째 쓰고 id 셋과 캠프만 바꾼다(불변규칙 8 — 손으로 만든 봉투는 발행자와 갈라질 수 있다). */
    private static String waveClosed(UUID eventId, UUID waveId) {
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
                .replace("01a04dad-80da-704e-b002-b9fbfbfdbe93", CAMP_ID.toString());
    }

    /** {@code PlanExecutionIT.seedCandidates} 와 같다 — 약속 창의 기준은 {@link PlanningClock#PLAN_AT}. */
    private int seedCandidates(UUID waveId, int count) {
        Instant now = PlanningClock.PLAN_AT.truncatedTo(ChronoUnit.MICROS);
        TimeWindow window = new TimeWindow(now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(5)));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                candidates.insertIfAbsent(DispatchCandidate.load(Ids.newId(), waveId, CAMP_ID, null,
                        GeoPoint.of(CAMP.lat() + 0.005d * (i + 1), CAMP.lng() + 0.004d * (i + 1)),
                        1_000, 2_000, false, false, window, 60, false, 0, now));
            }
        });
        return count;
    }
}
