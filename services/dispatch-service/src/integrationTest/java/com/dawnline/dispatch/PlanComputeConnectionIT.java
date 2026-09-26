package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.config.DispatchProperties;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.dispatch.domain.optimizer.DistanceProvider;
import com.dawnline.dispatch.domain.optimizer.HaversineDistance;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 최적화기가 도는 동안 계획은 DB 트랜잭션도 커넥션도 쥐지 않는다 (DESIGN.md §5.3, ADR-064).
 *
 * <p>계산은 순수 함수이고 {@code peak} 한 번이 19.9초다(phase4-repair-pruning). 그 동안 트랜잭션이 열려 있으면 커넥션 하나가
 * {@code idle in transaction} 으로 풀(인스턴스당 10, §8.2)에서 빠지고, 그 시각은 §8.2 가 말하는 컷오프 직전 버스트다.
 *
 * <p>재는 방법: 거리 제공자를 감싸 <strong>계산 안에서</strong> 멈춘다 — 최적화기가 거리를 처음 묻는 순간이다. 멈춘 동안 바깥에서
 * {@code pg_stat_activity} 를 본다. 짐작이 아니라 DB 가 보는 상태다: 계산을 덮는 트랜잭션이 있으면 그 백엔드는 멈춘 내내
 * {@code idle in transaction} 이다.
 *
 * <p>이 테스트가 막는 것: 누가 계산을 트랜잭션 안으로 되돌리면(예: 유스케이스에 {@code @Transactional} 을 다시 붙이면) 여기가
 * 빨개진다. 2026-09-26 에 이 테스트는 정정 전 코드에서 그 모양으로 빨갰다 — 그것이 ADR-064 의 근거 관측이다.
 */
@SpringBootTest(classes = DispatchApplication.class)
@Import({PlanningClock.class, PlanComputeConnectionIT.GatedDistance.class})
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("PlanComputeConnectionIT — 계산 중에는 트랜잭션이 없다")
class PlanComputeConnectionIT extends DispatchIntegrationTestBase {

    /**
     * 릴레이를 끈다 — 이 클래스는 발행을 보지 않는다. 켜 두면 릴레이의 폴링이 짧은 트랜잭션을 여닫아 관측에 섞인다.
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void relayOff(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    private static final String WAVE_CLOSED = "dawnline.wave.closed.v1";
    private static final EventContracts CONTRACTS = EventContracts.load();

    /** 시드의 첫 캠프 (서울 북부) — {@code PlanCrashIT} 와 같다. */
    private static final UUID CAMP_ID = UUID.fromString("01a06edd-6c00-7000-8001-000000000001");
    private static final GeoPoint CAMP = GeoPoint.of(37.640000, 127.030000);

    /** 이 DB 에서 트랜잭션을 연 채 아무것도 하지 않는 백엔드 — 테스트 자신의 연결은 뺀다. */
    private static final String IDLE_IN_TX_SQL = """
            SELECT pid, now() - xact_start AS age, query FROM pg_stat_activity
             WHERE datname = current_database() AND pid <> pg_backend_pid()
               AND state = 'idle in transaction'
            """;

    private static KafkaProducer<String, String> producer;

    static {
        createTopics(WAVE_CLOSED);
    }

    /** 최적화기가 거리를 처음 물을 때 멈추는 거리 제공자. 무장했을 때만 한 번 멈춘다. */
    @TestConfiguration
    static class GatedDistance {

        static final AtomicBoolean ARMED = new AtomicBoolean();
        static volatile CountDownLatch entered = new CountDownLatch(1);
        static volatile CountDownLatch release = new CountDownLatch(1);

        @Bean
        @Primary
        DistanceProvider gatedDistance(DispatchProperties properties) {
            DistanceProvider real = new HaversineDistance(properties.distance().roadFactor(),
                    properties.distance().averageSpeedKmh());
            return (from, to) -> {
                if (ARMED.compareAndSet(true, false)) {
                    entered.countDown();
                    try {
                        if (!release.await(60, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("테스트가 계산을 풀어 주지 않았다");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                return real.between(from, to);
            };
        }
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
    void arm() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String table : new String[] {"plan_explanations", "route_stop_orders", "route_stops", "routes",
                    "route_plans", "dispatch_candidates", "processed_events", "outbox_events"}) {
                s.executeUpdate("DELETE FROM " + table);
            }
        }
        GatedDistance.entered = new CountDownLatch(1);
        GatedDistance.release = new CountDownLatch(1);
        GatedDistance.ARMED.set(true);
    }

    @AfterEach
    void disarm() {
        GatedDistance.ARMED.set(false);
        GatedDistance.release.countDown();
    }

    @Test
    void 최적화기가_도는_동안_계획은_트랜잭션을_열어_두지_않는다() throws Exception {
        UUID waveId = Ids.newId();
        int seeded = seedCandidates(waveId, 5);

        producer.send(new ProducerRecord<>(WAVE_CLOSED, CAMP_ID.toString(), waveClosed(Ids.newId(), waveId))).get();

        // 전제를 먼저 말한다 — 계획이 계산 안에서 멈췄다. 이것이 없으면 아래의 「열린 트랜잭션 0」은 계획이 아직 시작하지
        // 않았거나 이미 끝난 순간을 본 것일 수 있다.
        assertThat(GatedDistance.entered.await(60, TimeUnit.SECONDS)).as("계획이 최적화기의 거리 질의에 닿았다").isTrue();
        assertThat(planStatus(waveId)).as("아직 발행되지 않았다 — 멈춘 것은 계산 도중이다").isEmpty();

        // 한 순간이 아니라 1초 동안 본다 — 계산을 덮는 트랜잭션이 있으면 그것은 멈춘 내내 열려 있다.
        List<String> held = new ArrayList<>();
        for (int i = 0; i < 5 && held.isEmpty(); i++) {
            held.addAll(idleInTransaction());
            Thread.sleep(200);
        }
        assertThat(held).as("계산 중에 트랜잭션을 연 채 쉬는 백엔드 — 커넥션 하나가 계산 시간만큼 풀에서 빠진다").isEmpty();

        GatedDistance.release.countDown();
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .until(() -> planStatus(waveId), status -> status.equals(Optional.of("PUBLISHED")));
        assertThat(count("SELECT count(*) FROM dispatch_candidates WHERE wave_id = ? AND status IN ('PLANNED', 'UNASSIGNED')",
                waveId)).as("풀어 준 계획은 끝까지 간다").isEqualTo(seeded);
    }

    private List<String> idleInTransaction() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(IDLE_IN_TX_SQL)) {
            while (rs.next()) {
                rows.add("pid=" + rs.getInt("pid") + " 나이=" + rs.getString("age") + " 마지막 질의=" + rs.getString("query"));
            }
        }
        return rows;
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
            s.setObject(1, parameter);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** 계약 예시를 봉투째 쓰고 id 셋과 캠프만 바꾼다 — {@code PlanCrashIT} 와 같다. */
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
