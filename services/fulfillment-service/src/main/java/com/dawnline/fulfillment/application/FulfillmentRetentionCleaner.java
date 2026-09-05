package com.dawnline.fulfillment.application;

import com.dawnline.fulfillment.application.port.out.FulfillmentOrderRepository;
import com.dawnline.fulfillment.application.port.out.WaveRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code fulfillment_orders} 30일 · {@code waves} 90일 보존 정리 — 일 1회
 * ([ADR-023](docs/adr/ADR-023-fulfillment-retention.md)).
 *
 * <h2>보존 기간을 정한 축은 정확성이 아니라 조사 가능성이다</h2>
 * 재전달 가능 창은 이미 {@code processed_events} 14일이 담당한다(§4.4). 이 표들이 더 오래 남는
 * 이유는 <strong>DLQ</strong> 다 — 운영자가 30일째에 {@code order.placed.v1.dlq} 레코드를 열었을 때
 * "주문 X 는 왜 웨이브에 없나" 에 답할 수 있어야 하고, 그 답을 담으려고 만든 표가 정작 그 순간에
 * 비어 있으면 안 된다. 그래서 DLQ 보존(§7.3, 30일)과 <em>같은 창</em>이다.
 *
 * <h2>30일에 지워도 정확성이 유지되는 이유는 이 표에 있지 않다</h2>
 * 재생·중복 {@code order.placed} 를 막는 것은 나이별로 셋이다 — ~14일 {@code processed_events},
 * 14~30일 {@code fulfillment_orders} PK, 30일~ <strong>{@code STALE_PLACED}</strong>(컷오프 + 24h,
 * ADR-020 후속 정정). 세 겹이 이어져 있어서 삭제가 안전한 것이고, 따라서
 * <strong>{@code STALE_PLACED} 의 24시간을 늘리는 변경은 이 30일을 함께 재검토해야 한다.</strong>
 *
 * <h2>순서가 있다: 주문 먼저, 웨이브 나중</h2>
 * {@code fulfillment_orders.wave_id} 가 {@code waves} 를 참조한다. 30일과 90일이라는 두 기간이
 * 이 순서를 자연히 만족시키지만, 그것은 <em>그렇게 고른 결과</em>이지 강제되는 성질이 아니다.
 * 그래서 순서를 코드로도 지키고, 웨이브 삭제는 참조 행이 없는 것만 지운다.
 *
 * <h2>배치마다 커밋한다</h2>
 * 두 가지 이유이고 두 번째는 측정으로 알게 된 것이다. (1) 긴 삭제 트랜잭션은 쓰기 경로(주문 편입)와
 * 같은 페이지를 두고 경쟁한다. (2) <strong>커밋해야 인덱스가 값을 한다</strong> — 한 트랜잭션 안에서
 * 배치를 반복하면 지운 인덱스 항목을 죽은 것으로 표시할 수 없어 k번째 배치가 앞선 k×batchSize 개를
 * 다시 훑는다(ADR-019 의 측정: 0.47초 vs 11.29초).
 */
public class FulfillmentRetentionCleaner {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentRetentionCleaner.class);

    private final FulfillmentOrderRepository orders;
    private final WaveRepository waves;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration orderRetention;
    private final Duration waveRetention;
    private final int batchSize;
    private final int maxBatchesPerRun;

    /**
     * @param orders             {@code fulfillment_orders} 저장소
     * @param waves              {@code waves} 저장소
     * @param transactionManager 배치마다 새 트랜잭션을 여는 데 쓴다
     * @param clock              임계 시각 계산 (불변규칙 12)
     * @param orderRetention     주문 행 보존 기간 (ADR-023 기본 30일)
     * @param waveRetention      웨이브 보존 기간 (ADR-023 기본 90일)
     * @param batchSize          한 트랜잭션에서 지울 최대 행 수
     * @param maxBatchesPerRun   한 번의 실행에서 반복할 최대 배치 수 (표마다 각각)
     */
    public FulfillmentRetentionCleaner(FulfillmentOrderRepository orders, WaveRepository waves,
            PlatformTransactionManager transactionManager, Clock clock,
            Duration orderRetention, Duration waveRetention, int batchSize, int maxBatchesPerRun) {

        this.orders = Objects.requireNonNull(orders, "orders");
        this.waves = Objects.requireNonNull(waves, "waves");
        Objects.requireNonNull(transactionManager, "transactionManager");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.orderRetention = requirePositive(orderRetention, "orderRetention");
        this.waveRetention = requirePositive(waveRetention, "waveRetention");
        if (orderRetention.compareTo(waveRetention) > 0) {
            // 주문이 웨이브보다 오래 남으면 웨이브 삭제가 매번 NOT EXISTS 에 막힌다. 설정 실수를
            // 기동 때 잡는다 — 정리가 조용히 아무것도 못 지우는 것보다 낫다.
            throw new IllegalArgumentException(
                    "주문 보존(%s)이 웨이브 보존(%s)보다 길 수 없습니다 — FK 방향과 어긋납니다"
                            .formatted(orderRetention, waveRetention));
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize 는 1 이상이어야 합니다: " + batchSize);
        }
        if (maxBatchesPerRun < 1) {
            throw new IllegalArgumentException("maxBatchesPerRun 은 1 이상이어야 합니다: " + maxBatchesPerRun);
        }
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 일 1회 정리.
     *
     * <p>초기 지연 기본값 10분은 {@code ProcessedEventCleaner}(5분)와 <strong>어긋나게</strong> 둔
     * 것이다. 둘 다 배치를 반복하느라 초 단위로 길어질 수 있고 같은 스케줄러 풀을 쓴다.
     *
     * <p>예외를 삼킨다. 정리 실패는 용량 문제지 정확성 문제가 아니므로 다음 실행이 이어받으면 된다.
     */
    @Scheduled(
            fixedDelayString = "${dawnline.fulfillment.retention.cleanup-interval-ms:86400000}",
            initialDelayString = "${dawnline.fulfillment.retention.cleanup-initial-delay-ms:600000}")
    public void cleanupExpired() {
        try {
            deleteExpired();
        } catch (RuntimeException e) {
            log.warn("보존 정리 실패. 다음 실행에서 이어서 지웁니다.", e);
        }
    }

    /**
     * 만료 행을 배치로 지운다. 스케줄과 무관하게 직접 호출할 수 있다(테스트·운영 수동 실행).
     *
     * @return 이번 실행에서 삭제된 행 수 (주문, 웨이브)
     */
    public Deleted deleteExpired() {
        Instant now = clock.instant();
        int deletedOrders = deleteInBatches("fulfillment_orders", now.minus(orderRetention),
                orders::deleteSettledUpdatedBefore);
        // 순서가 중요하다. 웨이브를 먼저 지우면 그것을 참조하는 주문 행이 남아 FK 위반이다.
        int deletedWaves = deleteInBatches("waves", now.minus(waveRetention),
                waves::deleteSettledClosedBefore);
        return new Deleted(deletedOrders, deletedWaves);
    }

    /**
     * 한 표의 만료 행을 배치로 지운다.
     *
     * <p>{@code limit} 만큼 못 채운 배치가 나오면 대상이 소진된 것이다 — 그것이 유일한 종료
     * 신호이고, 그래서 삭제 쿼리는 매번 <em>앞으로 나아가야</em> 한다(오래된 행부터 집는다).
     */
    private int deleteInBatches(String table, Instant threshold, BatchDelete delete) {
        int total = 0;
        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            Integer deleted = transactions.execute(status -> delete.apply(threshold, batchSize));
            int rows = deleted == null ? 0 : deleted;
            total += rows;
            if (rows < batchSize) {
                logResult(table, total, threshold, false);
                return total;
            }
        }
        logResult(table, total, threshold, true);
        return total;
    }

    private void logResult(String table, int total, Instant threshold, boolean hitCap) {
        if (hitCap) {
            log.info("{} {}건 삭제 (임계 {}). 한 실행 상한({}배치)에 걸려 남은 행은 다음 실행이 지운다.",
                    table, total, threshold, maxBatchesPerRun);
        } else if (total > 0) {
            log.info("{} {}건 삭제 (임계 {})", table, total, threshold);
        }
    }

    /** 배치 삭제 한 번. 표마다 조건이 다르고 리포지토리가 그 SQL 을 갖는다. */
    @FunctionalInterface
    private interface BatchDelete {
        int apply(Instant threshold, int limit);
    }

    /**
     * 한 실행의 삭제 결과.
     *
     * @param orders 삭제된 {@code fulfillment_orders} 행 수
     * @param waves  삭제된 {@code waves} 행 수
     */
    public record Deleted(int orders, int waves) {
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " 은 양수여야 합니다: " + value);
        }
        return value;
    }
}
