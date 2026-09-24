package com.dawnline.fulfillment;

import com.dawnline.fulfillment.domain.WaveCloseCause;
import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.fulfillment.adapter.out.persistence.JpaFulfillmentOrderRepository;
import com.dawnline.fulfillment.application.FulfillmentRetentionCleaner;
import com.dawnline.fulfillment.application.port.out.FulfillmentOrderRepository;
import com.dawnline.fulfillment.application.port.out.WaveRepository;
import com.dawnline.fulfillment.domain.FulfillmentOrder;
import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.UnserviceableReason;
import com.dawnline.fulfillment.domain.Wave;
import com.dawnline.messaging.retention.RetentionAges;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
 * 보존 정리 ([ADR-023](docs/adr/ADR-023-fulfillment-retention.md)) — 실제 PostgreSQL 18.
 *
 * <p>단위 테스트는 배치 루프만 본다. 여기서 보는 것은 <strong>SQL 이 실제로 무엇을 지우는가</strong>다.
 *
 * <ol>
 *   <li>종결 상태만 지운다. 진행 중 주문은 나이와 무관하게 남는다 — 30일 넘게 열려 있는 웨이브는
 *       그 자체가 사고이고, 사고 상황에서 데이터를 먼저 지우는 정리가 최악이다.</li>
 *   <li>나이를 {@code created_at} 이 아니라 {@code updated_at} 으로 잰다. 접수가 30일 전이라도
 *       취소가 어제면 조사 대상은 어제 사건이다.</li>
 *   <li>웨이브 삭제가 참조 행을 남겨 두지 않는다. FK 가 깨지면 정리가 매일 죽는다.</li>
 *   <li>삭제가 인덱스를 탄다. 배치마다 전수 스캔이면 하루치 정리가 0.24초 대신 68초다.</li>
 *   <li>걸린 행 셈이 삭제의 정확한 여집합이다 — 보존 기간을 넘긴 행은 지워지거나 세어진다(ADR-058 결정 8).</li>
 * </ol>
 */
@SpringBootTest(classes = FulfillmentApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("FulfillmentRetentionIT — 30일·90일 보존 정리")
class FulfillmentRetentionIT extends FulfillmentIntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");
    private static final Duration ORDER_RETENTION = Duration.ofDays(30);
    private static final Duration WAVE_RETENTION = Duration.ofDays(90);
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

    private FulfillmentRetentionCleaner cleaner(int batchSize, int maxBatches) {
        return new FulfillmentRetentionCleaner(orders, waves, transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC), ORDER_RETENTION, WAVE_RETENTION, batchSize, maxBatches,
                new RetentionAges(new SimpleMeterRegistry(), Clock.fixed(NOW, ZoneOffset.UTC)),
                new SimpleMeterRegistry());
    }

    @BeforeEach
    void clean() {
        tx().executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM fulfillment_orders").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM waves").executeUpdate();
        });
    }

    /** 웨이브를 만들어 원하는 상태·마감 시각까지 옮긴다. */
    private Wave wave(Instant cutoffAt, java.util.function.Consumer<Wave> advance) {
        Wave wave = Wave.open(Ids.newId(), Ids.newId(), ServiceTier.DAWN, cutoffAt);
        tx().executeWithoutResult(status -> waves.insertIfAbsent(wave));
        tx().executeWithoutResult(status -> {
            Wave loaded = waves.findById(wave.id()).orElseThrow();
            advance.accept(loaded);
            waves.update(loaded);
        });
        return wave;
    }

    private Wave plannedWave(Instant cutoffAt) {
        return wave(cutoffAt, w -> {
            w.beginClosing();
            w.close(cutoffAt.plusSeconds(120), 0, WaveCloseCause.SCHEDULED);
            w.markPlanned();
        });
    }

    private UUID plannedOrder(Wave wave, Instant at) {
        UUID orderId = Ids.newId();
        tx().executeWithoutResult(status -> orders.insertIfAbsent(
                FulfillmentOrder.planned(orderId, Ids.newId(), wave.id(), wave.campId(), Ids.newId(),
                        Ids.newId(), wave.cutoffAt(), WINDOW, false, null, at)));
        return orderId;
    }

    private UUID unserviceableOrder(Instant at) {
        UUID orderId = Ids.newId();
        tx().executeWithoutResult(status -> orders.insertIfAbsent(FulfillmentOrder.unserviceable(
                orderId, Ids.newId(), UnserviceableReason.STALE_PLACED, null, at)));
        return orderId;
    }

    private boolean exists(UUID orderId) {
        return tx().execute(status -> orders.findById(orderId).isPresent());
    }

    private boolean waveExists(UUID waveId) {
        return tx().execute(status -> waves.findById(waveId).isPresent());
    }

    // --- 무엇을 지우는가 -------------------------------------------------------

    @Test
    void 종결_상태만_지운다() {
        Instant old = NOW.minus(Duration.ofDays(31));
        Wave settled = plannedWave(old);
        Wave stillOpen = wave(old, w -> { });

        UUID doneUnserviceable = unserviceableOrder(old);
        UUID donePlanned = plannedOrder(settled, old);
        UUID inProgress = plannedOrder(stillOpen, old);

        FulfillmentRetentionCleaner.Deleted deleted = cleaner(100, 10).deleteExpired();

        assertThat(deleted.orders()).isEqualTo(2);
        assertThat(exists(doneUnserviceable)).isFalse();
        assertThat(exists(donePlanned)).isFalse();
        assertThat(exists(inProgress))
                .as("웨이브가 아직 열려 있으면 진행 중이다. 30일이 지나도 지우지 않는다")
                .isTrue();
    }

    @Test
    void 나이를_updated_at_으로_잰다() {
        // 접수는 60일 전, 취소는 어제. 조사 대상은 어제 사건이므로 남긴다.
        Wave settled = plannedWave(NOW.minus(Duration.ofDays(60)));
        UUID orderId = plannedOrder(settled, NOW.minus(Duration.ofDays(60)));
        tx().executeWithoutResult(status -> {
            FulfillmentOrder loaded = orders.findById(orderId).orElseThrow();
            loaded.cancel(NOW.minus(Duration.ofDays(1)));
            orders.update(loaded);
        });

        FulfillmentRetentionCleaner.Deleted deleted = cleaner(100, 10).deleteExpired();

        assertThat(deleted.orders()).isZero();
        assertThat(exists(orderId)).isTrue();
    }

    @Test
    void 아직_계획_중인_웨이브의_주문은_남는다() {
        // CLOSED 는 "마감됐다" 이지 "하류가 계획을 끝냈다" 가 아니다. 그 구분이 ADR-023 의 표다.
        Wave closed = wave(NOW.minus(Duration.ofDays(31)), w -> {
            w.beginClosing();
            w.close(NOW.minus(Duration.ofDays(31)).plusSeconds(120), 0, WaveCloseCause.SCHEDULED);
        });
        UUID orderId = plannedOrder(closed, NOW.minus(Duration.ofDays(31)));

        cleaner(100, 10).deleteExpired();

        assertThat(exists(orderId)).isTrue();
    }

    @Test
    void 보존_기간을_넘긴_행은_지워지거나_세어진다() {
        // 걸린 행 셈은 삭제 술어의 부정이다(ADR-058 결정 8). 두 문장이 따로 적혀 있으므로 한쪽 술어만 바뀌면
        // 어떤 행은 지워지지도 세어지지도 않는다 — 그 행이 조용히 영원히 남는다. 커밋되는 웨이브 상태 넷을 전부
        // 돈다(CLOSING 은 커밋되지 않는다 — FulfillmentMetrics.CAUSE_UNKNOWN).
        Instant old = NOW.minus(Duration.ofDays(31));
        Instant recent = NOW.minus(Duration.ofDays(1));
        Wave open = wave(old, w -> { });
        Wave closed = wave(old, w -> {
            w.beginClosing();
            w.close(old.plusSeconds(120), 0, WaveCloseCause.SCHEDULED);
        });
        Wave planned = plannedWave(old);
        Wave failed = wave(old, w -> {
            w.beginClosing();
            w.close(old.plusSeconds(120), 0, WaveCloseCause.SCHEDULED);
            w.markPlanFailed();
        });
        UUID cancelled = plannedOrder(open, old);
        tx().executeWithoutResult(status -> {
            FulfillmentOrder loaded = orders.findById(cancelled).orElseThrow();
            loaded.cancel(old);
            orders.update(loaded);
        });
        unserviceableOrder(old);
        UUID stuckOpen = plannedOrder(open, old);
        UUID stuckClosed = plannedOrder(closed, old);
        plannedOrder(planned, old);
        plannedOrder(failed, old);
        UUID young = plannedOrder(open, recent);

        // 지우기 전에 같은 행들을 센다. 정리기는 지운 뒤에 세므로 셈을 넓히는 결함(종결 행을 걸린 행으로 셈)은
        // 거기서는 보이지 않는다 — 그 행이 이미 지워져 있다. 그 결함이 드러나는 날은 배치 상한에 걸려 종결 행이
        // 남은 날이고, 그날 게이지가 부푼다. 그래서 여집합은 같은 스냅숏에서 본다: 지울 수 + 셀 수 = 넘긴 행 전부.
        Instant threshold = NOW.minus(ORDER_RETENTION);
        long countedBefore = tx().execute(status -> orders.countUnsettledUpdatedBefore(threshold));

        FulfillmentRetentionCleaner cleaner = cleaner(100, 10);
        FulfillmentRetentionCleaner.Deleted result = cleaner.deleteExpired();

        assertThat(result.orders()).as("취소·배차 불가·계획됨·계획 실패").isEqualTo(4);
        assertThat(countedBefore).as("열린 웨이브·마감만 된 웨이브 — 지우기 전에도 같은 둘").isEqualTo(2);
        assertThat(result.orders() + countedBefore).as("넘긴 행 여섯은 지워지거나 세어진다 — 둘 다는 없다").isEqualTo(6);
        assertThat(result.stuckOrders()).as("열린 웨이브·마감만 된 웨이브").isEqualTo(2);
        assertThat(cleaner.stuckOrders()).isEqualTo(2.0);
        assertThat(exists(stuckOpen)).isTrue();
        assertThat(exists(stuckClosed)).isTrue();
        assertThat(exists(young)).as("보존 기간 안 — 세지도 지우지도 않는다").isTrue();
        assertThat(countOrders())
                .as("남은 행 = 걸린 행 + 보존 기간 안의 행 — 틈이 없다")
                .isEqualTo(result.stuckOrders() + 1);
    }

    // --- 웨이브와 FK ----------------------------------------------------------

    @Test
    void 웨이브는_90일_뒤에_지워진다() {
        Wave old = plannedWave(NOW.minus(Duration.ofDays(91)));
        Wave recent = plannedWave(NOW.minus(Duration.ofDays(89)));

        FulfillmentRetentionCleaner.Deleted deleted = cleaner(100, 10).deleteExpired();

        assertThat(deleted.waves()).isEqualTo(1);
        assertThat(waveExists(old.id())).isFalse();
        assertThat(waveExists(recent.id())).isTrue();
    }

    @Test
    void 참조하는_주문이_남아_있으면_웨이브를_지우지_않는다() {
        // ADR-023 은 두 보존 기간 덕에 FK 가 자연히 만족된다고 적었지만, 그것은 그렇게 고른
        // 결과이지 강제되는 성질이 아니다. 가드가 없으면 이 상황에서 정리가 매일 FK 위반으로 죽는다.
        Wave old = plannedWave(NOW.minus(Duration.ofDays(91)));
        UUID recentlyTouched = plannedOrder(old, NOW.minus(Duration.ofDays(1)));

        FulfillmentRetentionCleaner.Deleted first = cleaner(100, 10).deleteExpired();

        assertThat(first.waves()).isZero();
        assertThat(waveExists(old.id())).isTrue();

        // 그 주문이 지워질 나이가 되면 웨이브도 따라 지워진다.
        tx().executeWithoutResult(status -> entityManager
                .createNativeQuery("DELETE FROM fulfillment_orders WHERE order_id = :id")
                .setParameter("id", recentlyTouched)
                .executeUpdate());
        FulfillmentRetentionCleaner.Deleted second = cleaner(100, 10).deleteExpired();

        assertThat(second.waves()).isEqualTo(1);
    }

    @Test
    void 주문을_먼저_지우기_때문에_FK_위반이_나지_않는다() {
        // 같은 실행 안에서 주문(30일) → 웨이브(90일) 순서다. 반대로 하면 여기서 예외가 난다.
        Wave old = plannedWave(NOW.minus(Duration.ofDays(91)));
        UUID orderId = plannedOrder(old, NOW.minus(Duration.ofDays(91)));

        FulfillmentRetentionCleaner.Deleted deleted = cleaner(100, 10).deleteExpired();

        assertThat(deleted.orders()).isEqualTo(1);
        assertThat(deleted.waves()).isEqualTo(1);
        assertThat(exists(orderId)).isFalse();
        assertThat(waveExists(old.id())).isFalse();
    }

    // --- 배치 --------------------------------------------------------------

    @Test
    void 상한에_걸리면_남은_행은_다음_실행이_지운다() {
        Wave settled = plannedWave(NOW.minus(Duration.ofDays(31)));
        for (int i = 0; i < 25; i++) {
            plannedOrder(settled, NOW.minus(Duration.ofDays(31)));
        }

        FulfillmentRetentionCleaner.Deleted first = cleaner(10, 2).deleteExpired();
        assertThat(first.orders()).isEqualTo(20);

        FulfillmentRetentionCleaner.Deleted second = cleaner(10, 2).deleteExpired();
        assertThat(second.orders()).isEqualTo(5);

        assertThat(countOrders()).isZero();
    }

    // --- 계획 --------------------------------------------------------------

    @Test
    void 삭제가_updated_at_인덱스를_탄다() {
        // 인덱스가 없으면 배치마다 테이블 전체를 다시 훑는다. 4.65M 행에서 하루치 정리가 0.24초
        // 대신 68초다(docs/benchmarks/phase2-fulfillment-orders-indexes.md §1).
        //
        // 작은 테이블에서는 순차 스캔이 맞는 판단이라 계획을 단정할 수 없다. 운영에 가까운 크기까지
        // 채우고 ANALYZE 한 뒤에 본다.
        Wave settled = plannedWave(NOW.minus(Duration.ofDays(31)));
        tx().executeWithoutResult(status -> entityManager.createNativeQuery("""
                INSERT INTO fulfillment_orders (order_id, status, wave_id, camp_id, promise_revised,
                                                version, created_at, updated_at)
                SELECT gen_random_uuid(), 'PLANNED', :waveId, gen_random_uuid(), false, 0,
                       timestamptz '2026-06-01 00:00:00Z',
                       timestamptz '2026-06-01 00:00:00Z' + (n || ' seconds')::interval
                  FROM generate_series(1, 100000) n""")
                .setParameter("waveId", settled.id())
                .executeUpdate());
        tx().executeWithoutResult(status ->
                entityManager.createNativeQuery("ANALYZE fulfillment_orders").executeUpdate());

        String plan = explain("""
                SELECT fo.ctid FROM fulfillment_orders fo
                 WHERE fo.updated_at < timestamptz '2026-06-01 06:00:00Z'
                   AND (fo.status IN ('CANCELLED', 'UNSERVICEABLE')
                        OR EXISTS (SELECT 1 FROM waves w
                                    WHERE w.id = fo.wave_id AND w.status IN ('PLANNED', 'PLAN_FAILED')))
                 ORDER BY fo.updated_at
                 LIMIT 1000""");

        assertThat(plan).as("계획: %s", plan).contains("ix_fulfillment_orders_cleanup");
        assertThat(plan).as("정렬이 사라져야 한다. 계획: %s", plan).doesNotContain("Sort Key");
    }

    @Test
    void 걸린_행_셈이_updated_at_인덱스를_탄다() {
        // 삭제 뒤에 세므로 임계보다 오래된 범위에 남는 것은 걸린 행뿐이다 — 운영에서는 거의 비어 있다. 그 범위를
        // 인덱스로 집지 않으면 하루 한 번의 셈이 30일치(약 465만 행)를 전부 훑는다. 하루 한 번 도는 문장이라
        // custom 계획이 도는 것이고 그것만 본다(docs/benchmarks/phase7-retention-indexes.md §3, rm_orders 셈과 같은
        // 판단). 픽스처는 운영 분포다: 대부분이 보존 기간 안이고 그 밖은 걸린 행 몇 개다.
        Wave open = wave(NOW.minus(Duration.ofDays(31)), w -> { });
        for (int i = 0; i < 3; i++) {
            plannedOrder(open, NOW.minus(Duration.ofDays(31)));
        }
        tx().executeWithoutResult(status -> entityManager.createNativeQuery("""
                INSERT INTO fulfillment_orders (order_id, status, wave_id, camp_id, promise_revised,
                                                version, created_at, updated_at)
                SELECT gen_random_uuid(), 'PLANNED', :waveId, gen_random_uuid(), false, 0,
                       timestamptz '2026-08-10 00:00:00Z',
                       timestamptz '2026-08-10 00:00:00Z' + (n * 20 || ' seconds')::interval
                  FROM generate_series(1, 100000) n""")
                .setParameter("waveId", open.id())
                .executeUpdate());
        tx().executeWithoutResult(status ->
                entityManager.createNativeQuery("ANALYZE fulfillment_orders").executeUpdate());
        assertThat(reltuples())
                .as("통계가 있다 — 없으면 플래너가 짐작으로 고른다(불변규칙 11)")
                .isGreaterThan(50_000);

        // 어댑터의 문장 그대로 — 복사본을 재면 문장이 바뀌어도 이 검사는 초록이다. 하루 한 번이라 custom 만 본다.
        String plan = customPlan(JpaFulfillmentOrderRepository.COUNT_UNSETTLED_SQL,
                "timestamptz '2026-08-06 00:00:00Z'");

        assertThat(plan).as("계획: %s", plan).contains("ix_fulfillment_orders_cleanup");
        assertThat(plan).as("계획: %s", plan).doesNotContain("Seq Scan on fulfillment_orders");
    }

    /** 어댑터의 문장을 준비하고({@code :threshold} → {@code $1}) custom 계획만 본다 — 실행하지 않는다. */
    private String customPlan(String sql, String threshold) {
        String prepared = sql.replace(":threshold", "$1");
        return tx().execute(status -> {
            entityManager.createNativeQuery("SET LOCAL plan_cache_mode = force_custom_plan").executeUpdate();
            entityManager.createNativeQuery("PREPARE retention_probe(timestamptz) AS " + prepared).executeUpdate();
            try {
                List<?> rows = entityManager.createNativeQuery(
                        "EXPLAIN EXECUTE retention_probe(" + threshold + ")").getResultList();
                return String.join("\n", rows.stream().map(String::valueOf).toList());
            } finally {
                entityManager.createNativeQuery("DEALLOCATE retention_probe").executeUpdate();
                status.setRollbackOnly();
            }
        });
    }

    private float reltuples() {
        Number value = tx().execute(status -> (Number) entityManager.createNativeQuery(
                "SELECT reltuples FROM pg_class WHERE relname = 'fulfillment_orders'").getSingleResult());
        return value.floatValue();
    }

    private long countOrders() {
        Number count = tx().execute(status -> (Number) entityManager
                .createNativeQuery("SELECT count(*) FROM fulfillment_orders").getSingleResult());
        return count.longValue();
    }

    private String explain(String sql) {
        List<?> rows = tx().execute(status ->
                entityManager.createNativeQuery("EXPLAIN " + sql).getResultList());
        return String.join("\n", rows.stream().map(String::valueOf).toList());
    }
}
