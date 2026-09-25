package com.dawnline.ops.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.messaging.retention.ManualClock;
import com.dawnline.messaging.retention.RetentionAges;
import com.dawnline.observability.DawnlineMetrics;
import com.dawnline.ops.application.port.out.ReadModelRetention;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * {@link ReadModelRetentionCleaner} — 순서 · 임계 · 걸린 행 게이지 · 실패의 모양 (ADR-058).
 *
 * <p>무엇이 지워지는지(종결 술어 · NULL · 가드)는 SQL 의 일이라 {@code ReadModelRetentionIT} 가 실제 PostgreSQL 에서
 * 본다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("ReadModelRetentionCleaner — rm_orders 90일 · 상한 365일 · rm_routes·rm_waves 90일")
class ReadModelRetentionCleanerTest {

    private static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration DAYS_90 = Duration.ofDays(90);

    private final RecordingRetention retention = new RecordingRetention();
    private final ManualClock ageClock = new ManualClock(NOW);
    private final RetentionAges ages = new RetentionAges(new SimpleMeterRegistry(), ageClock);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private ReadModelRetentionCleaner cleaner(int batchSize, int maxBatches) {
        return cleanerWith(DAYS_90, Duration.ofDays(365), DAYS_90, DAYS_90, batchSize, maxBatches);
    }

    private ReadModelRetentionCleaner cleanerWith(Duration orders, Duration cap, Duration routes, Duration waves,
            int batchSize, int maxBatches) {
        return new ReadModelRetentionCleaner(retention, new NoOpTransactionManager(), CLOCK, orders, cap, routes, waves,
                batchSize, maxBatches, ages, meters);
    }

    @Test
    void 주문을_먼저_지우고_라우트_웨이브를_나중에_지운_뒤_센다() {
        cleaner(1000, 10).deleteExpired();

        assertThat(retention.calls).containsExactly("settled", "cap", "routes", "waves", "stuck");
    }

    @Test
    void 임계는_각자의_보존_기간이고_걸린_행은_주문_보존으로_센다() {
        cleaner(1000, 10).deleteExpired();

        assertThat(retention.thresholds).containsExactly(
                NOW.minus(DAYS_90),              // 종결 · 라우트 · 웨이브 · 걸린 행
                NOW.minus(Duration.ofDays(365))); // 상한
        assertThat(retention.stuckThreshold).isEqualTo(NOW.minus(DAYS_90));
    }

    @Test
    void 배치를_반복하고_덜_찬_배치에서_멈춘다_단계마다_상한이_있다() {
        retention.settledRows = 2500;
        retention.routeRows = 10_000;

        ReadModelRetentionCleaner.Result result = cleaner(1000, 3).deleteExpired();

        assertThat(result.settledOrders()).isEqualTo(2500);
        assertThat(result.routes()).as("상한 3배치").isEqualTo(3000);
    }

    @Test
    void 걸린_행_게이지는_세기_전에는_NaN_이다() {
        cleaner(1000, 10);

        assertThat(meters.get(DawnlineMetrics.RM_ORDERS_STUCK.meterName()).gauge().value())
                .as("0 은 「걸린 것이 없다」는 주장이다 — 아직 세지 않았다")
                .isNaN();
    }

    @Test
    void 걸린_행_게이지는_센_값을_낸다() {
        retention.stuck = 7;
        ReadModelRetentionCleaner cleaner = cleaner(1000, 10);

        cleaner.deleteExpired();

        assertThat(meters.get(DawnlineMetrics.RM_ORDERS_STUCK.meterName()).gauge().value()).isEqualTo(7.0);
    }

    @Test
    void 실패하면_걸린_행_게이지가_NaN_으로_돌아가고_성공_나이가_자란다() {
        retention.stuck = 7;
        ReadModelRetentionCleaner cleaner = cleaner(1000, 10);
        cleaner.deleteExpired();
        retention.failRoutes = true;
        ageClock.advance(Duration.ofDays(2));

        cleaner.cleanupExpired();

        assertThat(meters.get(DawnlineMetrics.RM_ORDERS_STUCK.meterName()).gauge().value())
                .as("멈춘 값은 건강해 보인다 — 모르면 NaN")
                .isNaN();
        assertThat(ages.table("rm_orders").ageSeconds()).as("주문 단계는 끝까지 돌았다").isZero();
        assertThat(ages.table("rm_routes").ageSeconds()).isEqualTo(2 * 86400.0);
        assertThat(ages.table("rm_waves").ageSeconds()).as("라우트 뒤라 돌지 않았다").isEqualTo(2 * 86400.0);
    }

    @Test
    void 직접_호출은_예외를_그대로_올린다() {
        retention.failRoutes = true;

        assertThatThrownBy(() -> cleaner(1000, 10).deleteExpired()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 부모_보존이_주문보다_짧으면_기동에서_막는다() {
        assertThatThrownBy(() -> cleanerWith(DAYS_90, Duration.ofDays(365), Duration.ofDays(30), DAYS_90, 1000, 10))
                .hasMessageContaining("먼저 지워져야");
        assertThatThrownBy(() -> cleanerWith(DAYS_90, Duration.ofDays(365), DAYS_90, Duration.ofDays(30), 1000, 10))
                .hasMessageContaining("먼저 지워져야");
    }

    @Test
    void 상한이_주문_보존보다_짧으면_기동에서_막는다() {
        assertThatThrownBy(() -> cleanerWith(DAYS_90, Duration.ofDays(30), DAYS_90, DAYS_90, 1000, 10))
                .hasMessageContaining("상한");
    }

    @Test
    void 잘못된_설정을_생성자가_막는다() {
        assertThatThrownBy(() -> cleaner(0, 10)).hasMessageContaining("batchSize");
        assertThatThrownBy(() -> cleaner(1000, 0)).hasMessageContaining("maxBatchesPerRun");
    }

    /** 호출과 임계만 기록한다. 지울 행 수는 시험이 정한다. */
    private static final class RecordingRetention implements ReadModelRetention {

        private final List<String> calls = new ArrayList<>();
        private final List<Instant> thresholds = new ArrayList<>();
        private Instant stuckThreshold;
        private int settledRows;
        private int routeRows;
        private long stuck;
        private boolean failRoutes;

        @Override
        public int deleteSettledOrdersUpdatedBefore(Instant updatedBefore, int limit) {
            record("settled", updatedBefore);
            int rows = Math.min(settledRows, limit);
            settledRows -= rows;
            return rows;
        }

        @Override
        public int deleteOrdersUpdatedBefore(Instant updatedBefore, int limit) {
            record("cap", updatedBefore);
            return 0;
        }

        @Override
        public int deleteUnreferencedRoutesUpdatedBefore(Instant updatedBefore, int limit) {
            record("routes", updatedBefore);
            if (failRoutes) {
                throw new IllegalStateException("DB 불가");
            }
            int rows = Math.min(routeRows, limit);
            routeRows -= rows;
            return rows;
        }

        @Override
        public int deleteUnreferencedWavesUpdatedBefore(Instant updatedBefore, int limit) {
            record("waves", updatedBefore);
            return 0;
        }

        @Override
        public long countStuckOrdersUpdatedBefore(Instant updatedBefore) {
            calls.add("stuck");
            stuckThreshold = updatedBefore;
            return stuck;
        }

        private void record(String call, Instant threshold) {
            if (calls.isEmpty() || !calls.getLast().equals(call)) {
                calls.add(call);
            }
            if (!thresholds.contains(threshold)) {
                thresholds.add(threshold);
            }
        }
    }

    private static final class NoOpTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
