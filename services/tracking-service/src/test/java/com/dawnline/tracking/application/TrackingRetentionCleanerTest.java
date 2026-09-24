package com.dawnline.tracking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.messaging.retention.ManualClock;
import com.dawnline.messaging.retention.RetentionAges;
import com.dawnline.tracking.application.port.out.TrackingRetention;
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
 * {@link TrackingRetentionCleaner} — 순서 · 임계 · 배치 · 실패의 모양 (ADR-058).
 *
 * <p>무엇이 지워지는지(종결만 · 가드)는 SQL 의 일이라 {@code TrackingRetentionIT} 가 실제 PostgreSQL 에서 본다.
 * 여기서는 정리기가 그 SQL 을 <em>어떤 순서로 어떤 임계로</em> 부르는지를 본다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("TrackingRetentionCleaner — shipments 30일 · 상한 365일 · route_revisions 90일")
class TrackingRetentionCleanerTest {

    private static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final RecordingRetention retention = new RecordingRetention();
    private final ManualClock ageClock = new ManualClock(NOW);
    private final RetentionAges ages = new RetentionAges(new SimpleMeterRegistry(), ageClock);

    private TrackingRetentionCleaner cleaner(int batchSize, int maxBatches) {
        return cleanerWith(Duration.ofDays(30), Duration.ofDays(365), Duration.ofDays(90), batchSize, maxBatches);
    }

    private TrackingRetentionCleaner cleanerWith(Duration shipments, Duration cap, Duration revisions,
            int batchSize, int maxBatches) {
        return new TrackingRetentionCleaner(retention, new NoOpTransactionManager(), CLOCK, shipments, cap, revisions,
                batchSize, maxBatches, ages);
    }

    @Test
    void 세_임계를_각자의_보존_기간으로_계산한다() {
        cleaner(1000, 10).deleteExpired();

        assertThat(retention.thresholds).containsExactly(
                NOW.minus(Duration.ofDays(30)),
                NOW.minus(Duration.ofDays(365)),
                NOW.minus(Duration.ofDays(90)));
    }

    @Test
    void 배송을_먼저_지우고_개정을_나중에_지운다() {
        // 개정의 가드(NOT EXISTS shipments)는 배송이 먼저 사라져야 넘을 수 있다.
        cleaner(1000, 10).deleteExpired();

        assertThat(retention.calls).containsExactly("settled", "cap", "revisions");
    }

    @Test
    void 배치를_반복하고_덜_찬_배치에서_멈춘다() {
        retention.settledRows = 2500;

        TrackingRetentionCleaner.Deleted deleted = cleaner(1000, 10).deleteExpired();

        assertThat(deleted.settledShipments()).isEqualTo(2500);
        assertThat(retention.calls).containsExactly("settled", "settled", "settled", "cap", "revisions");
    }

    @Test
    void 한_실행의_배치_수에_상한이_있다_단계마다() {
        retention.settledRows = 10_000;
        retention.revisionRows = 10_000;

        TrackingRetentionCleaner.Deleted deleted = cleaner(1000, 2).deleteExpired();

        assertThat(deleted).isEqualTo(new TrackingRetentionCleaner.Deleted(2000, 0, 2000));
    }

    @Test
    void 끝까지_돈_실행은_두_표의_성공_나이를_0_으로_되돌린다() {
        TrackingRetentionCleaner cleaner = cleaner(1000, 10);
        ageClock.advance(Duration.ofDays(2));

        cleaner.cleanupExpired();

        assertThat(ages.table("shipments").ageSeconds()).isZero();
        assertThat(ages.table("route_revisions").ageSeconds()).isZero();
    }

    @Test
    void 실패는_삼키되_실패한_표와_그_뒤의_표의_나이가_자란다() {
        TrackingRetentionCleaner cleaner = cleaner(1000, 10);
        retention.failCap = true;
        ageClock.advance(Duration.ofDays(2));

        cleaner.cleanupExpired();

        // 종결 단계는 돌았지만 표의 정리는 상한 단계까지가 한 단위다 — 개정은 그 뒤라 돌지 않았다.
        assertThat(retention.calls).containsExactly("settled", "cap");
        assertThat(ages.table("shipments").ageSeconds()).isEqualTo(2 * 86400.0);
        assertThat(ages.table("route_revisions").ageSeconds()).isEqualTo(2 * 86400.0);
    }

    @Test
    void 직접_호출은_예외를_그대로_올린다() {
        retention.failCap = true;

        assertThatThrownBy(() -> cleaner(1000, 10).deleteExpired()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 개정_보존이_배송_보존보다_짧으면_기동에서_막는다() {
        // 개정이 등호인 30일의 둘째 방어다 — 먼저 만료되면 그 방어가 설정 하나로 사라진다.
        assertThatThrownBy(() -> cleanerWith(Duration.ofDays(30), Duration.ofDays(365), Duration.ofDays(20), 1000, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("둘째 방어");
    }

    @Test
    void 상한이_배송_보존보다_짧으면_기동에서_막는다() {
        assertThatThrownBy(() -> cleanerWith(Duration.ofDays(30), Duration.ofDays(20), Duration.ofDays(90), 1000, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("상한");
    }

    @Test
    void 잘못된_설정을_생성자가_막는다() {
        assertThatThrownBy(() -> cleaner(0, 10)).hasMessageContaining("batchSize");
        assertThatThrownBy(() -> cleaner(1000, 0)).hasMessageContaining("maxBatchesPerRun");
        assertThatThrownBy(() -> cleanerWith(Duration.ZERO, Duration.ofDays(365), Duration.ofDays(90), 1000, 10))
                .hasMessageContaining("shipments");
    }

    /** 호출과 임계만 기록한다. 지울 행 수는 시험이 정한다. */
    private static final class RecordingRetention implements TrackingRetention {

        private final List<String> calls = new ArrayList<>();
        private final List<Instant> thresholds = new ArrayList<>();
        private int settledRows;
        private int revisionRows;
        private boolean failCap;

        @Override
        public int deleteSettledShipmentsUpdatedBefore(Instant updatedBefore, int limit) {
            record("settled", updatedBefore);
            int rows = Math.min(settledRows, limit);
            settledRows -= rows;
            return rows;
        }

        @Override
        public int deleteShipmentsUpdatedBefore(Instant updatedBefore, int limit) {
            record("cap", updatedBefore);
            if (failCap) {
                throw new IllegalStateException("DB 불가");
            }
            return 0;
        }

        @Override
        public int deleteUnreferencedRevisionsAppliedBefore(Instant appliedBefore, int limit) {
            record("revisions", appliedBefore);
            int rows = Math.min(revisionRows, limit);
            revisionRows -= rows;
            return rows;
        }

        private void record(String call, Instant threshold) {
            calls.add(call);
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
