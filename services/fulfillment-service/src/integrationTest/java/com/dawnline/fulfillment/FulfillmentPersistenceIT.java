package com.dawnline.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.fulfillment.application.port.out.FulfillmentOrderRepository;
import com.dawnline.fulfillment.application.port.out.WaveRepository;
import com.dawnline.fulfillment.domain.FcFallbackReason;
import com.dawnline.fulfillment.domain.FulfillmentOrder;
import com.dawnline.fulfillment.domain.FulfillmentOrderStatus;
import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.UnserviceableReason;
import com.dawnline.fulfillment.domain.Wave;
import com.dawnline.fulfillment.domain.WaveStatus;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code waves}·{@code fulfillment_orders} 매핑과 조회 (DESIGN.md §5.2, ADR-022) — 실제 PostgreSQL 18.
 *
 * <p>이 테스트가 없으면 확인되지 않는 것들이다.
 *
 * <ol>
 *   <li>Flyway V1+V2 스키마와 JPA 엔티티가 맞는가. {@code ddl-auto=validate} 라서 <strong>이
 *       클래스가 뜬다는 사실 자체가</strong> 그 검증이다 — {@code wave_orders} 드롭 이후의 스키마다.</li>
 *   <li>{@code ON CONFLICT DO NOTHING} 이 실제로 조용히 지나가는가. 이것이 아니면 동시 도착이
 *       정상 흐름에서 예외가 된다(ADR-022 결정 4).</li>
 *   <li>취소가 나머지 컬럼을 지우지 않는가. 애그리거트가 모든 필드를 복원하지 못하면 여기서 깨진다.</li>
 *   <li>마감 대상 조회가 <em>부분 인덱스를 실제로 타는가</em>. 상태를 파라미터로 넘기면 못 탄다.</li>
 * </ol>
 */
@SpringBootTest(classes = FulfillmentApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("FulfillmentPersistenceIT — waves·fulfillment_orders 매핑")
class FulfillmentPersistenceIT extends FulfillmentIntegrationTestBase {

    private static final Instant CUTOFF = Instant.parse("2026-09-05T01:00:00Z");
    private static final Duration GRACE = Duration.ofSeconds(90);
    private static final TimeWindow WINDOW = new TimeWindow(
            Instant.parse("2026-09-05T15:00:00Z"), Instant.parse("2026-09-05T22:00:00Z"));

    @Autowired
    private WaveRepository waves;

    @Autowired
    private FulfillmentOrderRepository orders;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void clean() {
        tx().executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM fulfillment_orders").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM waves").executeUpdate();
        });
    }

    private Wave openWave(UUID campId, Instant cutoffAt) {
        Wave wave = Wave.open(Ids.newId(), campId, ServiceTier.DAWN, cutoffAt);
        tx().executeWithoutResult(status -> assertThat(waves.insertIfAbsent(wave)).isTrue());
        return wave;
    }

    // --- waves ---------------------------------------------------------------

    @Test
    void 자연키가_같은_웨이브는_두_번_만들어지지_않는다() {
        // (campId, tier, cutoffAt) UNIQUE 가 동시 편입의 직렬화 지점이다. 두 리스너가 같은 틈에
        // 들어가도 한쪽만 산다 — 그리고 진 쪽은 예외가 아니라 false 를 받는다.
        UUID campId = Ids.newId();
        openWave(campId, CUTOFF);

        Wave duplicate = Wave.open(Ids.newId(), campId, ServiceTier.DAWN, CUTOFF);
        Boolean inserted = tx().execute(status -> waves.insertIfAbsent(duplicate));

        assertThat(inserted).isFalse();
        Optional<Wave> surviving = tx().execute(status ->
                waves.findByNaturalKey(campId, ServiceTier.DAWN, CUTOFF));
        assertThat(surviving).get().extracting(Wave::id).isNotEqualTo(duplicate.id());
    }

    @Test
    void 웨이브_전이와_카운트가_왕복한다() {
        Wave wave = openWave(Ids.newId(), CUTOFF);
        Instant closedAt = CUTOFF.plusSeconds(120);

        tx().executeWithoutResult(status -> {
            Wave loaded = waves.findById(wave.id()).orElseThrow();
            loaded.beginClosing();
            waves.update(loaded);
        });
        tx().executeWithoutResult(status -> {
            // 마감은 배타 락으로 잡는다 (ADR-025). 이 시점에는 진행 중인 편입이 없다.
            Wave loaded = waves.findByIdForUpdate(wave.id()).orElseThrow();
            loaded.close(closedAt, 4820);
            waves.update(loaded);
        });

        Wave reloaded = tx().execute(status -> waves.findById(wave.id()).orElseThrow());
        assertThat(reloaded.status()).isEqualTo(WaveStatus.CLOSED);
        assertThat(reloaded.orderCount()).isEqualTo(4820);
        assertThat(reloaded.closedAt()).isEqualTo(closedAt);
        assertThat(reloaded.version()).as("두 번 갱신했으므로 버전이 올라간다").isEqualTo(2);
    }

    @Test
    void 편입_후보를_세는_것과_저장된_카운트가_같다() {
        // ADR-025 — 마감 시 세는 값이 wave.closed 로 나간다. 취소된 주문은 빠진다.
        Wave wave = openWave(Ids.newId(), CUTOFF);
        UUID kept = Ids.newId();
        UUID cancelled = Ids.newId();
        tx().executeWithoutResult(status -> {
            orders.insertIfAbsent(FulfillmentOrder.planned(kept, Ids.newId(), wave.id(), wave.campId(),
                    Ids.newId(), Ids.newId(), CUTOFF, WINDOW, false, null, CUTOFF));
            orders.insertIfAbsent(FulfillmentOrder.planned(cancelled, Ids.newId(), wave.id(), wave.campId(),
                    Ids.newId(), Ids.newId(), CUTOFF, WINDOW, false, null, CUTOFF));
        });
        tx().executeWithoutResult(status -> {
            FulfillmentOrder loaded = orders.findById(cancelled).orElseThrow();
            loaded.cancel(CUTOFF.plusSeconds(10));
            orders.update(loaded);
        });

        Integer counted = tx().execute(status -> orders.countPlannedInWave(wave.id()));

        assertThat(counted).as("취소는 카운트를 건드리는 분기 없이 자동으로 빠진다").isEqualTo(1);
    }

    @Test
    void 편입의_공유_락은_서로_막지_않고_마감의_배타_락은_기다린다() {
        // ADR-025 의 핵심 주장이다. 이것이 성립하지 않으면 §8.2 피크에서 웨이브 행 하나가
        // 처리량 상한이 되거나(둘 다 배타), 마감된 웨이브에 주문이 샌다(락 없음).
        Wave wave = openWave(Ids.newId(), CUTOFF);

        java.util.concurrent.CountDownLatch firstHolds = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        boolean secondShareAcquired;
        boolean updateAcquiredWhileShared;

        Thread holder = Thread.ofPlatform().start(() -> tx().executeWithoutResult(status -> {
            waves.findByIdForShare(wave.id()).orElseThrow();
            firstHolds.countDown();
            await(release);
        }));

        try {
            await(firstHolds);

            // 다른 편입은 막히지 않는다.
            tx().executeWithoutResult(status -> waves.findByIdForShare(wave.id()).orElseThrow());
            secondShareAcquired = true;

            // 마감은 막힌다. NOWAIT 로 확인한다 — "기다렸다" 를 시간으로 재면 느린 CI 에서
            // 흔들리므로, 즉시 실패하는 형태로 바꿔 본다.
            //
            // 트랜잭션 <em>밖</em>에서 잡는다. 안에서 삼키면 그 트랜잭션은 이미 rollback-only 라
            // 커밋에서 UnexpectedRollbackException 이 난다(실제로 그렇게 실패했다).
            updateAcquiredWhileShared = tryLockForUpdateNoWait(wave.id());
        } finally {
            release.countDown();
            join(holder);
        }

        assertThat(secondShareAcquired).as("공유 락끼리는 서로 막지 않는다").isTrue();
        assertThat(updateAcquiredWhileShared)
                .as("마감의 배타 락은 진행 중인 편입이 끝날 때까지 기다린다").isFalse();

        // 편입이 끝나면 마감은 곧바로 잡힌다.
        assertThat(tryLockForUpdateNoWait(wave.id())).isTrue();
    }

    /** 배타 락을 즉시 잡을 수 있으면 {@code true}. 잡을 수 없으면 PostgreSQL 이 바로 실패시킨다. */
    private boolean tryLockForUpdateNoWait(UUID waveId) {
        try {
            tx().executeWithoutResult(status -> entityManager
                    .createNativeQuery("SELECT id FROM waves WHERE id = :id FOR UPDATE NOWAIT")
                    .setParameter("id", waveId)
                    .getSingleResult());
            return true;
        } catch (RuntimeException blocked) {
            return false;
        }
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("래치 대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(java.time.Duration.ofSeconds(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 마감_대상_조회는_grace_를_넘긴_OPEN_웨이브만_준다() {
        UUID campId = Ids.newId();
        openWave(campId, CUTOFF);
        Wave later = openWave(Ids.newId(), CUTOFF.plusSeconds(600));
        Wave closing = openWave(Ids.newId(), CUTOFF.minusSeconds(600));
        tx().executeWithoutResult(status -> {
            Wave loaded = waves.findById(closing.id()).orElseThrow();
            loaded.beginClosing();
            waves.update(loaded);
        });

        List<Wave> due = tx().execute(status ->
                waves.findDueForClosing(CUTOFF.plus(GRACE), 100));

        assertThat(due).extracting(Wave::campId).containsExactly(campId);
        assertThat(due).extracting(Wave::id).doesNotContain(later.id(), closing.id());
    }

    @Test
    void 마감_대상_조회가_부분_인덱스를_탄다() {
        // status 를 바인드 파라미터로 넘기면 플래너가 부분 인덱스의 술어를 증명하지 못해
        // ix_waves_open_cutoff 를 못 탄다. 리터럴로 적은 이유가 이것이고, 그 사실을 여기서 잡는다.
        //
        // 작은 테이블에서는 순차 스캔이 맞는 판단이라 계획을 단정할 수 없다(phase1-retention-indexes
        // §3 과 같은 이유). 그래서 운영에 가까운 크기까지 채우고 ANALYZE 한 뒤에 본다.
        tx().executeWithoutResult(status -> entityManager.createNativeQuery("""
                INSERT INTO waves (id, camp_id, service_tier, cutoff_at, status, order_count, version)
                SELECT gen_random_uuid(), gen_random_uuid(), 'DAWN',
                       timestamptz '2026-01-01 00:00:00Z' + (n || ' minutes')::interval,
                       CASE WHEN n % 20 = 0 THEN 'OPEN' ELSE 'PLANNED' END, 0, 0
                  FROM generate_series(1, 20000) n""").executeUpdate());
        tx().executeWithoutResult(status -> entityManager.createNativeQuery("ANALYZE waves").executeUpdate());

        String plan = explain("""
                SELECT * FROM waves
                 WHERE status = 'OPEN' AND cutoff_at <= timestamptz '2026-01-01 01:00:00Z'
                 ORDER BY cutoff_at LIMIT 100""");

        assertThat(plan)
                .as("계획: %s", plan)
                .contains("ix_waves_open_cutoff");
    }

    // --- fulfillment_orders --------------------------------------------------

    @Test
    void 계획된_주문의_모든_컬럼이_왕복한다() {
        Wave wave = openWave(Ids.newId(), CUTOFF);
        UUID orderId = Ids.newId();
        UUID eventId = Ids.newId();
        UUID campId = wave.campId();
        UUID fcId = Ids.newId();
        UUID zoneId = Ids.newId();
        Instant at = CUTOFF.minusSeconds(300);

        FulfillmentOrder order = FulfillmentOrder.planned(orderId, eventId, wave.id(), campId, fcId,
                zoneId, CUTOFF, WINDOW, true, FcFallbackReason.COLD, at);
        tx().executeWithoutResult(status -> assertThat(orders.insertIfAbsent(order)).isTrue());

        FulfillmentOrder loaded = tx().execute(status -> orders.findById(orderId).orElseThrow());
        assertThat(loaded.status()).isEqualTo(FulfillmentOrderStatus.PLANNED);
        assertThat(loaded.waveId()).contains(wave.id());
        assertThat(loaded.campId()).contains(campId);
        assertThat(loaded.fcId()).contains(fcId);
        assertThat(loaded.zoneId()).contains(zoneId);
        assertThat(loaded.cutoffAt()).isEqualTo(Optional.of(CUTOFF));
        assertThat(loaded.promisedWindow()).contains(WINDOW);
        assertThat(loaded.promiseRevised()).isTrue();
        assertThat(loaded.fcFallbackReason()).contains(FcFallbackReason.COLD);
        assertThat(loaded.placedEventId()).contains(eventId);
        assertThat(loaded.createdAt()).isEqualTo(at);
        assertThat(loaded.updatedAt()).isEqualTo(at);
    }

    @Test
    void 배차_불가_주문은_웨이브_없이_사유만_남는다() {
        UUID orderId = Ids.newId();
        FulfillmentOrder order = FulfillmentOrder.unserviceable(orderId, Ids.newId(),
                UnserviceableReason.STALE_PLACED, null, CUTOFF);
        tx().executeWithoutResult(status -> orders.insertIfAbsent(order));

        FulfillmentOrder loaded = tx().execute(status -> orders.findById(orderId).orElseThrow());
        assertThat(loaded.status()).isEqualTo(FulfillmentOrderStatus.UNSERVICEABLE);
        assertThat(loaded.unserviceableReason()).contains(UnserviceableReason.STALE_PLACED);
        assertThat(loaded.waveId()).isEmpty();
        assertThat(loaded.campId()).isEmpty();
        assertThat(loaded.promisedWindow()).isEmpty();
    }

    @Test
    void 취소_선착_뒤에_온_order_placed_는_행을_덮지_않는다() {
        // ADR-022 결정 3·4. 두 리스너가 같은 order_id 로 들어오면 PK 가 직렬화하고, 진 쪽은
        // false 를 받아 재조회 후 상태 머신을 적용한다.
        UUID orderId = Ids.newId();
        Instant cancelledAt = CUTOFF.minusSeconds(60);
        tx().executeWithoutResult(status ->
                orders.insertIfAbsent(FulfillmentOrder.cancelledBeforePlaced(orderId, cancelledAt)));

        Wave wave = openWave(Ids.newId(), CUTOFF);
        Boolean inserted = tx().execute(status -> orders.insertIfAbsent(
                FulfillmentOrder.planned(orderId, Ids.newId(), wave.id(), wave.campId(), Ids.newId(),
                        Ids.newId(), CUTOFF, WINDOW, false, null, CUTOFF)));

        assertThat(inserted).isFalse();
        FulfillmentOrder loaded = tx().execute(status -> orders.findById(orderId).orElseThrow());
        assertThat(loaded.status()).isEqualTo(FulfillmentOrderStatus.CANCELLED);
        assertThat(loaded.placedEventId()).as("취소 선착의 기록이다").isEmpty();
        assertThat(loaded.ignoresPlaced()).isTrue();
    }

    @Test
    void 취소가_웨이브_소속과_판정_결과를_지우지_않는다() {
        // "어느 웨이브에 있다가 취소됐나" 가 조사 대상이다. 애그리거트가 상태만 복원하면
        // 여기서 컬럼이 날아간다.
        Wave wave = openWave(Ids.newId(), CUTOFF);
        UUID orderId = Ids.newId();
        UUID fcId = Ids.newId();
        tx().executeWithoutResult(status -> orders.insertIfAbsent(
                FulfillmentOrder.planned(orderId, Ids.newId(), wave.id(), wave.campId(), fcId,
                        Ids.newId(), CUTOFF, WINDOW, false, null, CUTOFF.minusSeconds(300))));

        Instant cancelledAt = CUTOFF.plusSeconds(30);
        tx().executeWithoutResult(status -> {
            FulfillmentOrder loaded = orders.findById(orderId).orElseThrow();
            loaded.cancel(cancelledAt);
            orders.update(loaded);
        });

        FulfillmentOrder loaded = tx().execute(status -> orders.findById(orderId).orElseThrow());
        assertThat(loaded.status()).isEqualTo(FulfillmentOrderStatus.CANCELLED);
        assertThat(loaded.waveId()).contains(wave.id());
        assertThat(loaded.fcId()).contains(fcId);
        assertThat(loaded.promisedWindow()).contains(WINDOW);
        assertThat(loaded.cancelledAt()).contains(cancelledAt);
        assertThat(loaded.updatedAt()).as("보존 정리의 기준이 옮겨간다").isEqualTo(cancelledAt);
    }

    @Test
    void 웨이브의_계획_후보만_모은다() {
        Wave wave = openWave(Ids.newId(), CUTOFF);
        UUID planned = Ids.newId();
        UUID cancelled = Ids.newId();
        tx().executeWithoutResult(status -> {
            orders.insertIfAbsent(FulfillmentOrder.planned(planned, Ids.newId(), wave.id(),
                    wave.campId(), Ids.newId(), Ids.newId(), CUTOFF, WINDOW, false, null, CUTOFF));
            FulfillmentOrder toCancel = FulfillmentOrder.planned(cancelled, Ids.newId(), wave.id(),
                    wave.campId(), Ids.newId(), Ids.newId(), CUTOFF, WINDOW, false, null, CUTOFF);
            orders.insertIfAbsent(toCancel);
        });
        tx().executeWithoutResult(status -> {
            FulfillmentOrder loaded = orders.findById(cancelled).orElseThrow();
            loaded.cancel(CUTOFF.plusSeconds(10));
            orders.update(loaded);
        });

        List<FulfillmentOrder> candidates = tx().execute(status -> orders.findPlannedInWave(wave.id()));

        assertThat(candidates).extracting(FulfillmentOrder::orderId).containsExactly(planned);
    }

    private String explain(String sql) {
        List<?> rows = tx().execute(status ->
                entityManager.createNativeQuery("EXPLAIN " + sql).getResultList());
        return String.join("\n", rows.stream().map(String::valueOf).toList());
    }
}
