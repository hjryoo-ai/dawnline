package com.dawnline.fulfillment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.fulfillment.application.port.out.FulfillmentOrderRepository;
import com.dawnline.fulfillment.application.port.out.WaveRepository;
import com.dawnline.fulfillment.domain.FulfillmentOrder;
import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.Wave;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 보존 정리의 배치 루프 (ADR-023). DB 는 IT 가 본다. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FulfillmentRetentionCleanerTest {

    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final RecordingRepositories repositories = new RecordingRepositories();

    private FulfillmentRetentionCleaner cleaner(int batchSize, int maxBatches) {
        return new FulfillmentRetentionCleaner(repositories.orders(), repositories.waves(),
                new NoOpTransactionManager(), CLOCK, Duration.ofDays(30), Duration.ofDays(90),
                batchSize, maxBatches);
    }

    @Test
    void 두_임계_시각을_각자의_보존_기간으로_계산한다() {
        repositories.orderRowsToDelete = 0;
        repositories.waveRowsToDelete = 0;

        cleaner(1000, 10).deleteExpired();

        assertThat(repositories.orderThresholds).containsExactly(NOW.minus(Duration.ofDays(30)));
        assertThat(repositories.waveThresholds).containsExactly(NOW.minus(Duration.ofDays(90)));
    }

    @Test
    void 주문을_먼저_지우고_웨이브를_나중에_지운다() {
        // fulfillment_orders.wave_id 가 waves 를 참조한다. 반대로 하면 FK 위반이다.
        cleaner(1000, 10).deleteExpired();

        assertThat(repositories.callOrder).containsExactly("orders", "waves");
    }

    @Test
    void 배치를_반복하고_덜_찬_배치에서_멈춘다() {
        // limit 을 못 채운 배치가 유일한 종료 신호다.
        repositories.orderRowsToDelete = 2500;

        FulfillmentRetentionCleaner.Deleted deleted = cleaner(1000, 10).deleteExpired();

        assertThat(deleted.orders()).isEqualTo(2500);
        assertThat(repositories.orderThresholds).as("1000·1000·500 세 배치").hasSize(3);
    }

    @Test
    void 한_실행의_배치_수에_상한이_있다() {
        // 상한에 걸리면 남은 행은 다음 실행이 지운다. 정리는 정확성이 아니라 용량 관리다.
        repositories.orderRowsToDelete = 1_000_000;

        FulfillmentRetentionCleaner.Deleted deleted = cleaner(1000, 3).deleteExpired();

        assertThat(deleted.orders()).isEqualTo(3000);
        assertThat(repositories.orderThresholds).hasSize(3);
    }

    @Test
    void 스케줄_진입점은_예외를_삼킨다() {
        repositories.failOrders = true;

        cleaner(1000, 10).cleanupExpired();

        // 예외가 밖으로 나가면 스케줄러 스레드가 그 작업을 더는 돌리지 않는다.
        assertThat(repositories.callOrder).containsExactly("orders");
    }

    @Test
    void 주문_보존이_웨이브_보존보다_길면_기동에서_막는다() {
        // 이 설정이면 웨이브 삭제가 매번 NOT EXISTS 에 막혀 아무것도 못 지운다. 조용히 도는 것보다
        // 기동 실패가 낫다.
        assertThatThrownBy(() -> new FulfillmentRetentionCleaner(repositories.orders(), repositories.waves(),
                new NoOpTransactionManager(), CLOCK, Duration.ofDays(120), Duration.ofDays(90), 1000, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FK 방향");
    }

    @Test
    void 잘못된_설정을_생성자가_막는다() {
        assertThatThrownBy(() -> cleanerWith(Duration.ZERO, Duration.ofDays(90), 1000, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cleanerWith(Duration.ofDays(30), Duration.ofDays(90), 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cleanerWith(Duration.ofDays(30), Duration.ofDays(90), 1000, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private FulfillmentRetentionCleaner cleanerWith(Duration orders, Duration waves, int batch, int max) {
        return new FulfillmentRetentionCleaner(repositories.orders(), repositories.waves(),
                new NoOpTransactionManager(), CLOCK, orders, waves, batch, max);
    }

    /** 삭제 호출만 기록하는 가짜. 나머지 메서드는 이 테스트의 관심이 아니다. */
    private static final class RecordingRepositories {

        private final List<Instant> orderThresholds = new ArrayList<>();
        private final List<Instant> waveThresholds = new ArrayList<>();
        private final List<String> callOrder = new ArrayList<>();
        private int orderRowsToDelete;
        private int waveRowsToDelete;
        private boolean failOrders;

        FulfillmentOrderRepository orders() {
            return new FulfillmentOrderRepository() {
                @Override
                public boolean insertIfAbsent(FulfillmentOrder order) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Optional<FulfillmentOrder> findById(UUID orderId) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public List<FulfillmentOrder> findPlannedInWave(UUID waveId) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void update(FulfillmentOrder order) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int deleteSettledUpdatedBefore(Instant updatedBefore, int limit) {
                    if (callOrder.isEmpty() || !"orders".equals(callOrder.get(callOrder.size() - 1))) {
                        callOrder.add("orders");
                    }
                    if (failOrders) {
                        throw new IllegalStateException("삭제 실패");
                    }
                    orderThresholds.add(updatedBefore);
                    int deleted = Math.min(limit, orderRowsToDelete);
                    orderRowsToDelete -= deleted;
                    return deleted;
                }
            };
        }

        WaveRepository waves() {
            return new WaveRepository() {
                @Override
                public boolean insertIfAbsent(Wave wave) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Optional<Wave> findByNaturalKey(UUID campId, ServiceTier tier, Instant cutoffAt) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Optional<Wave> findById(UUID id) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Optional<Wave> findByIdForUpdate(UUID id) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public List<Wave> findDueForClosing(Instant cutoffAtOrBefore, int limit) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void update(Wave wave) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int deleteSettledClosedBefore(Instant closedBefore, int limit) {
                    if (callOrder.isEmpty() || !"waves".equals(callOrder.get(callOrder.size() - 1))) {
                        callOrder.add("waves");
                    }
                    waveThresholds.add(closedBefore);
                    int deleted = Math.min(limit, waveRowsToDelete);
                    waveRowsToDelete -= deleted;
                    return deleted;
                }
            };
        }
    }

    /** 배치마다 트랜잭션을 여는 것 자체는 IT 가 본다. 여기서는 루프만 본다. */
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
