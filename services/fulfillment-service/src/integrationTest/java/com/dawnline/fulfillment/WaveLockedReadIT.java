package com.dawnline.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.fulfillment.application.port.in.PlacedOrderSnapshot;
import com.dawnline.fulfillment.application.port.in.PlanOrderUseCase;
import com.dawnline.fulfillment.application.port.out.WaveRepository;
import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.Wave;
import com.dawnline.fulfillment.domain.WaveStatus;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 잠그며 읽은 웨이브는 <strong>잠근 순간의 행</strong>이다 — 같은 트랜잭션이 앞서 읽어 둔 낡은 사본이 아니다 (ADR-025).
 *
 * <p>편입(공유 락)과 마감(배타 락)의 정확성은 「잠근 뒤에 본 상태」에 기대어 있다. 그런데 두 경로 모두 잠그기 <em>전에</em> 같은 웨이브를
 * 한 번 읽는다 — 편입은 자연키로 찾고(없으면 만든다), 운영자 마감은 「다음 웨이브인가」를 먼저 본다. 그 읽기가 엔티티를 영속성 컨텍스트에
 * 올려 두면, 잠금 질의가 돌려주는 것은 <strong>그 사본</strong>이다 — Hibernate 는 이미 관리 중인 엔티티를 질의 결과로 덮어쓰지 않는다.
 * 락은 걸리지만(다른 쪽 커밋을 기다린다) 그 뒤에 보는 상태는 기다리기 전의 것이다.
 *
 * <p>근거: 관측(재현됨) — 2026-09-26 CI 에서 {@code WaveLifecycleIT} 의 「운영자와 스케줄러가 동시에 닫는다」가 운영자 쪽에서
 * {@code wave-not-open}(409) 대신 {@code OptimisticLockException} 으로 끝났다: 낡은 OPEN 을 보고 닫으려다 {@code version} 검사에
 * 걸렸다. 편입 쪽은 웨이브를 쓰지 않으므로 그 검사조차 없다 — 닫힌 웨이브에 주문이 <strong>조용히 들어간다</strong>. 아래 셋째 테스트가
 * 그 모양을 인터리빙을 고정해 재현한다.
 */
@SpringBootTest(classes = FulfillmentApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("WaveLockedReadIT — 잠그며 읽은 웨이브는 잠근 순간의 행이다")
class WaveLockedReadIT extends FulfillmentIntegrationTestBase {

    /** 시드된 캠프 (R__seed_fulfillment) — 편입 경로가 권역을 찾으려면 시드된 캠프여야 한다. */
    private static final UUID SEEDED_CAMP = UUID.fromString("01a06edd-6c00-7000-8001-000000000001");

    /** 편입이 공유 락에서 기다리는 백엔드 — 테스트 자신의 연결은 뺀다. */
    private static final String BLOCKED_ON_SHARE_SQL = """
            SELECT count(*) FROM pg_stat_activity
             WHERE datname = current_database() AND pid <> pg_backend_pid()
               AND wait_event_type = 'Lock' AND query ILIKE '%FROM waves WHERE id =%FOR SHARE%'
            """;

    /**
     * 발행을 보지 않는다 — 릴레이를 끈다(다른 IT 와 advisory lock 을 다투지 않게).
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void relayOff(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    @Autowired
    private WaveRepository waves;

    @Autowired
    private PlanOrderUseCase planOrder;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    /** 이 클래스가 만든 웨이브와 주문 — 끝나면 지운다(자연키가 테스트마다 달라 남의 행이 아니다). */
    private final List<UUID> ownWaves = new ArrayList<>();
    private final List<UUID> ownOrders = new ArrayList<>();

    @AfterEach
    void 만든_것을_지운다() {
        tx().executeWithoutResult(status -> {
            for (UUID id : ownOrders) {
                entityManager.createNativeQuery("DELETE FROM outbox_events WHERE aggregate_id = ?1").setParameter(1, id)
                        .executeUpdate();
                entityManager.createNativeQuery("DELETE FROM fulfillment_orders WHERE order_id = ?1").setParameter(1, id)
                        .executeUpdate();
            }
            for (UUID id : ownWaves) {
                entityManager.createNativeQuery("DELETE FROM outbox_events WHERE aggregate_id = ?1").setParameter(1, id)
                        .executeUpdate();
                entityManager.createNativeQuery("DELETE FROM fulfillment_orders WHERE wave_id = ?1").setParameter(1, id)
                        .executeUpdate();
                entityManager.createNativeQuery("DELETE FROM waves WHERE id = ?1").setParameter(1, id).executeUpdate();
            }
        });
        ownOrders.clear();
        ownWaves.clear();
    }

    @Test
    void 공유_락으로_읽은_웨이브는_먼저_읽은_사본이_아니라_지금의_행이다() {
        Wave wave = openWave();

        WaveStatus seen = tx().execute(status -> {
            assertThat(waves.findByNaturalKey(wave.campId(), wave.serviceTier(), wave.cutoffAt()))
                    .as("전제 — 잠그기 전에 한 번 읽었다(편입 경로의 모양)").get()
                    .extracting(Wave::status).isEqualTo(WaveStatus.OPEN);
            closeFromAnotherConnection(wave.id());
            return waves.findByIdForShare(wave.id()).orElseThrow().status();
        });

        assertThat(seen).as("잠근 순간의 행 — 다른 쪽이 이미 닫았다").isEqualTo(WaveStatus.CLOSED);
    }

    @Test
    void 배타_락으로_읽은_웨이브는_먼저_읽은_사본이_아니라_지금의_행이다() {
        Wave wave = openWave();

        WaveStatus seen = tx().execute(status -> {
            assertThat(waves.findById(wave.id())).as("전제 — 잠그기 전에 한 번 읽었다(운영자 마감 경로의 모양)").get()
                    .extracting(Wave::status).isEqualTo(WaveStatus.OPEN);
            closeFromAnotherConnection(wave.id());
            return waves.findByIdForUpdate(wave.id()).orElseThrow().status();
        });

        assertThat(seen).isEqualTo(WaveStatus.CLOSED);
    }

    @Test
    void 편입이_공유_락을_기다리는_동안_닫힌_웨이브에는_주문이_들어가지_않는다() throws Exception {
        // 인터리빙을 고정한다: 이 테스트의 연결이 웨이브에 배타 락을 쥔다 → 편입이 자연키로 찾고(락 없는 읽기라 지나간다) 공유 락에서
        // 기다린다 → 그 사이에 이 연결이 웨이브를 닫고 커밋한다 → 편입이 풀려난다. 편입이 볼 것은 CLOSED 이고, 다음 컷오프로 밀려야 한다.
        Instant cutoffAt = uniqueCutoff(Duration.ofMinutes(20));
        String geohash7 = anyZoneGeohash5() + "bc";
        PlacedOrderSnapshot first = snapshot(geohash7, cutoffAt);
        ownOrders.add(first.orderId());
        PlanOrderUseCase.PlanOutcome opened = planOrder.plan(first, Ids.newId());
        assertThat(opened.kind()).as("전제 — 시드된 권역이라 계획된다").isEqualTo(PlanOrderUseCase.PlanOutcome.Kind.PLANNED);
        UUID waveId = opened.waveId().orElseThrow();
        ownWaves.add(waveId);
        assertThat(opened.revised()).as("전제 — 이 컷오프의 웨이브가 열려 있었다").isFalse();

        PlacedOrderSnapshot late = snapshot(geohash7, cutoffAt);
        ownOrders.add(late.orderId());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection closer = dataSource.getConnection()) {
            closer.setAutoCommit(false);
            try (PreparedStatement lock = closer.prepareStatement("SELECT id FROM waves WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, waveId);
                lock.executeQuery().close();
            }
            CompletableFuture<PlanOrderUseCase.PlanOutcome> admission =
                    CompletableFuture.supplyAsync(() -> planOrder.plan(late, Ids.newId()), pool);

            // 전제를 먼저 말한다 — 편입이 공유 락에서 기다린다. 아니면 아래 결과는 인터리빙이 아니라 순서를 본 것이다.
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                    .until(() -> count(BLOCKED_ON_SHARE_SQL) == 1);

            try (PreparedStatement close = closer.prepareStatement("""
                    UPDATE waves SET status = 'CLOSED', closed_at = now(), close_cause = 'SCHEDULED', version = version + 1
                     WHERE id = ?""")) {
                close.setObject(1, waveId);
                assertThat(close.executeUpdate()).isOne();
            }
            closer.commit();

            PlanOrderUseCase.PlanOutcome outcome = admission.get(30, TimeUnit.SECONDS);
            // 밀려 간 웨이브는 지우지 않는다 — 다음 컷오프는 스케줄의 실제 값이라 다른 IT 의 웨이브일 수 있다.
            assertThat(outcome.waveId()).as("닫힌 웨이브에 편입되지 않는다 — 다음 컷오프로 민다(ADR-025)")
                    .isPresent().get().isNotEqualTo(waveId);
            assertThat(outcome.revised()).as("밀렸으니 약속이 개정된다").isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM fulfillment_orders WHERE wave_id = '" + waveId + "'"))
                .as("닫힌 웨이브의 주문은 닫기 전의 하나뿐").isOne();
    }

    // --- 도우미 ---------------------------------------------------------------

    private Wave openWave() {
        Wave wave = Wave.open(Ids.newId(), SEEDED_CAMP, ServiceTier.SAME_DAY, uniqueCutoff(Duration.ofHours(3)));
        tx().executeWithoutResult(status -> waves.insertIfAbsent(wave));
        ownWaves.add(wave.id());
        return wave;
    }

    /** 이 테스트만의 컷오프 — 주입된 시계에서 뽑고(편입이 시계와 견준다) 자연키가 겹치지 않게 흩는다. */
    private Instant uniqueCutoff(Duration ahead) {
        return clock.instant().plus(ahead).plusMillis(Ids.newId().getLeastSignificantBits() & 0xFFFFF)
                .truncatedTo(ChronoUnit.MICROS);
    }

    /** 다른 쪽이 닫고 커밋한 것 — 스케줄러의 마감이 남기는 행의 모양(상태 · 시각 · 원인 · 버전). */
    private void closeFromAnotherConnection(UUID waveId) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement("""
                UPDATE waves SET status = 'CLOSED', closed_at = now(), close_cause = 'SCHEDULED', version = version + 1
                 WHERE id = ?""")) {
            s.setObject(1, waveId);
            assertThat(s.executeUpdate()).isOne();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private long count(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql);
                ResultSet rs = s.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private PlacedOrderSnapshot snapshot(String geohash7, Instant cutoffAt) {
        return new PlacedOrderSnapshot(Ids.newId(), Ids.newId(), "SAME_DAY",
                new PlacedOrderSnapshot.Address("서울 강남구 테헤란로 1", "06236",
                        new GeoPoint(37.4979, 127.0276), geohash7),
                new TimeWindow(cutoffAt, cutoffAt.plus(Duration.ofHours(6))),
                new PlacedOrderSnapshot.Parcel(1200, 8000, false, false),
                List.of(new PlacedOrderSnapshot.Item("SKU-00001", 1)),
                clock.instant(), cutoffAt);
    }

    private String anyZoneGeohash5() {
        return tx().execute(status -> ((String) entityManager
                .createNativeQuery("SELECT geohash5 FROM zones ORDER BY geohash5 LIMIT 1")
                .getSingleResult()).strip());
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }
}
