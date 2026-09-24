package com.dawnline.ops.application;

import com.dawnline.messaging.retention.RetentionAges;
import com.dawnline.ops.application.port.out.ReadModelRetention;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 읽기 모델 보존 정리 — {@code rm_orders} 종결 90일 · 상한 365일 · {@code rm_routes}·{@code rm_waves} 90일, 일 1회
 * ([ADR-058](docs/adr/ADR-058-shipment-and-read-model-retention.md), DESIGN.md §5.5 「updated_at」).
 *
 * <h2>90일에 지워도 되살아나지 않는 이유</h2>
 * 한 주문의 사실은 며칠 안에 끝난다(접수 → 배송 결과). 그 창 안에서 실패한 사실이 DLQ 에 들어갔다면 DLQ 보존
 * (30일)이 먼저 끝난다 — 마지막 쓰기 + 90일에 지울 때 그 레코드는 이미 없다. 거꾸로 돌면 ADR-051 의 「먼저 온
 * 사실이 행을 만든다」가 지운 주문의 반쪽 행을 만든다.
 *
 * <h2>순서가 있다: 주문 먼저, 라우트·웨이브 나중</h2>
 * 라우트·웨이브는 참조하는 주문 행이 없을 때만 지운다({@code NOT EXISTS}). 표끼리 FK 는 없지만(V1 머리말 규칙 3)
 * 주문 행이 남아 있는 라우트를 지우면 화면이 그 주문의 라우트를 잃는다. 기간이 이 순서를 자연히 만족시키지만
 * 그것은 고른 결과이지 강제되는 성질이 아니다 — 그래서 쿼리에 가드를 두고, 부모 보존이 주문보다 짧은 설정은
 * 기동에서 거부한다.
 *
 * <h2>비종결 행은 남기되 센다</h2>
 * 걸린 주문은 조사 대상이라 90일에 지우지 않는다. 그런데 그 행이 영원히 남으면 프로젝션 결손이 소리 없이
 * 누적된다 — 그래서 매 실행이 <strong>세고</strong>({@code dawnline_rm_orders_stuck}), 365일 상한에서 지운다.
 * 상한은 정리이지 정책이 아니다. 세기 전과 실행이 실패한 동안 게이지는 {@code NaN} 이다 — 0 은 「걸린 것이
 * 없다」는 주장이고, 멈춘 값은 건강해 보인다.
 *
 * <h2>실패는 삼키되 보이게</h2>
 * 표마다 끝까지 돈 정리만 {@code dawnline_retention_last_success_age_seconds{table}} 을 0 으로 되돌린다(결정 6).
 */
public class ReadModelRetentionCleaner {

    /** §9.1 — Prometheus 에서 {@code dawnline_rm_orders_stuck}. */
    public static final String STUCK = "dawnline.rm.orders.stuck";

    private static final Logger log = LoggerFactory.getLogger(ReadModelRetentionCleaner.class);

    private final ReadModelRetention retention;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration orders;
    private final Duration ordersCap;
    private final Duration routes;
    private final Duration waves;
    private final int batchSize;
    private final int maxBatchesPerRun;
    private final RetentionAges.Table orderAge;
    private final RetentionAges.Table routeAge;
    private final RetentionAges.Table waveAge;

    /** 마지막으로 센 걸린 행 수. 모르면 {@code NaN} 의 비트 — {@link Double#doubleToLongBits} 로 담는다. */
    private final AtomicLong stuck = new AtomicLong(Double.doubleToLongBits(Double.NaN));

    /**
     * @param retention          삭제와 셈의 포트
     * @param transactionManager 배치마다 새 트랜잭션을 여는 데 쓴다
     * @param clock              임계 시각 계산 (불변규칙 12)
     * @param orders             종결 주문 행 보존 (기본 90일)
     * @param ordersCap          비종결까지 지우는 상한 (기본 365일)
     * @param routes             라우트 보존 (기본 90일)
     * @param waves              웨이브 보존 (기본 90일)
     * @param batchSize          한 트랜잭션에서 지울 최대 행 수
     * @param maxBatchesPerRun   한 번의 실행에서 반복할 최대 배치 수 (단계마다 각각)
     * @param ages               성공 나이 게이지 — 생성하면서 세 표를 등록한다
     * @param meters             {@code dawnline_rm_orders_stuck} 을 등록할 레지스트리 — 기동 때 {@code NaN} 으로
     */
    public ReadModelRetentionCleaner(ReadModelRetention retention, PlatformTransactionManager transactionManager,
            Clock clock, Duration orders, Duration ordersCap, Duration routes, Duration waves, int batchSize,
            int maxBatchesPerRun, RetentionAges ages, MeterRegistry meters) {

        this.retention = Objects.requireNonNull(retention, "retention");
        Objects.requireNonNull(transactionManager, "transactionManager");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.orders = requirePositive(orders, "orders");
        this.ordersCap = requirePositive(ordersCap, "ordersCap");
        this.routes = requirePositive(routes, "routes");
        this.waves = requirePositive(waves, "waves");
        if (orders.compareTo(ordersCap) > 0) {
            throw new IllegalArgumentException(
                    "주문 보존(%s)이 상한(%s)보다 길 수 없습니다 — 상한이 종결 행을 먼저 지웁니다".formatted(orders, ordersCap));
        }
        if (orders.compareTo(routes) > 0 || orders.compareTo(waves) > 0) {
            // 부모가 먼저 만료되면 부모 삭제가 매번 NOT EXISTS 에 막힌다. 조용히 도는 것보다 기동 실패가 낫다.
            throw new IllegalArgumentException(
                    "주문 보존(%s)이 라우트(%s)·웨이브(%s) 보존보다 길 수 없습니다 — 주문이 먼저 지워져야 합니다"
                            .formatted(orders, routes, waves));
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
        Objects.requireNonNull(ages, "ages");
        this.orderAge = ages.table("rm_orders");
        this.routeAge = ages.table("rm_routes");
        this.waveAge = ages.table("rm_waves");
        Gauge.builder(STUCK, this, ReadModelRetentionCleaner::stuckOrders)
                .description("보존 기간을 넘겼는데 종결이 아닌 rm_orders 행 수 — 프로젝션 결손. 모르면 NaN (ADR-058).")
                .register(Objects.requireNonNull(meters, "meters"));
    }

    /**
     * 일 1회 정리. 초기 지연 15분은 outbox 정리(1분)·{@code processed_events} 정리(5분)와 어긋나게 둔 것이다.
     *
     * <p>예외를 삼킨다 — 다음 실행이 이어받는다. 삼킨 실패는 성공 나이 게이지와 걸린 행 게이지({@code NaN})가
     * 말한다.
     */
    @Scheduled(
            fixedDelayString = "${dawnline.ops.retention.cleanup-interval-ms:86400000}",
            initialDelayString = "${dawnline.ops.retention.cleanup-initial-delay-ms:900000}")
    public void cleanupExpired() {
        try {
            deleteExpired();
        } catch (RuntimeException e) {
            log.warn("읽기 모델 보존 정리 실패. 다음 실행에서 이어서 지웁니다.", e);
        }
    }

    /**
     * 만료 행을 배치로 지우고 걸린 행을 센다. 스케줄과 무관하게 직접 호출할 수 있다(테스트·운영 수동 실행).
     *
     * <p>실패하면 걸린 행 게이지를 {@code NaN} 으로 되돌리고 예외를 그대로 올린다 — 모르는 값을 마지막 값으로
     * 남겨 두지 않는다.
     *
     * @return 이번 실행의 결과
     */
    public Result deleteExpired() {
        try {
            return run();
        } catch (RuntimeException e) {
            stuck.set(Double.doubleToLongBits(Double.NaN));
            throw e;
        }
    }

    private Result run() {
        Instant now = clock.instant();
        int settled = deleteInBatches("rm_orders(종결)", now.minus(orders),
                retention::deleteSettledOrdersUpdatedBefore);
        int capped = deleteInBatches("rm_orders(상한)", now.minus(ordersCap), retention::deleteOrdersUpdatedBefore);
        orderAge.succeeded();
        // 순서가 중요하다. 주문 행이 먼저 사라져야 그 라우트·웨이브가 가드를 넘는다.
        int deletedRoutes = deleteInBatches("rm_routes", now.minus(routes),
                retention::deleteUnreferencedRoutesUpdatedBefore);
        routeAge.succeeded();
        int deletedWaves = deleteInBatches("rm_waves", now.minus(waves),
                retention::deleteUnreferencedWavesUpdatedBefore);
        waveAge.succeeded();
        long stuckOrders = Objects.requireNonNull(
                transactions.execute(status -> retention.countStuckOrdersUpdatedBefore(now.minus(orders))), "stuck");
        stuck.set(Double.doubleToLongBits(stuckOrders));
        if (stuckOrders > 0) {
            log.info("보존 기간({})을 넘긴 비종결 rm_orders {}건 — 프로젝션 결손이다. 상한({})에서 지운다.",
                    orders, stuckOrders, ordersCap);
        }
        return new Result(settled, capped, deletedRoutes, deletedWaves, stuckOrders);
    }

    /**
     * {@code dawnline_rm_orders_stuck} 게이지 값.
     *
     * @return 마지막으로 센 걸린 행 수. 세기 전이거나 마지막 실행이 실패했으면 {@code NaN}
     */
    public double stuckOrders() {
        return Double.longBitsToDouble(stuck.get());
    }

    /**
     * 한 단계의 만료 행을 배치로 지운다. {@code limit} 을 못 채운 배치가 대상 소진의 신호다.
     */
    private int deleteInBatches(String step, Instant threshold, BatchDelete delete) {
        int total = 0;
        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            Integer deleted = transactions.execute(status -> delete.apply(threshold, batchSize));
            int rows = deleted == null ? 0 : deleted;
            total += rows;
            if (rows < batchSize) {
                logResult(step, total, threshold, false);
                return total;
            }
        }
        logResult(step, total, threshold, true);
        return total;
    }

    private void logResult(String step, int total, Instant threshold, boolean hitCap) {
        if (hitCap) {
            log.info("{} {}건 삭제 (임계 {}). 한 실행 상한({}배치)에 걸려 남은 행은 다음 실행이 지운다.",
                    step, total, threshold, maxBatchesPerRun);
        } else if (total > 0) {
            log.info("{} {}건 삭제 (임계 {})", step, total, threshold);
        }
    }

    /** 배치 삭제 한 번. 단계마다 조건이 다르고 포트가 그 SQL 을 갖는다. */
    @FunctionalInterface
    private interface BatchDelete {
        int apply(Instant threshold, int limit);
    }

    /**
     * 한 실행의 결과.
     *
     * @param settledOrders 종결이라 지운 주문 행
     * @param cappedOrders  상한에 걸려 지운 주문 행 (비종결 포함)
     * @param routes        지운 라우트 행
     * @param waves         지운 웨이브 행
     * @param stuckOrders   보존 기간을 넘긴 비종결 주문 행 — 게이지의 값
     */
    public record Result(int settledOrders, int cappedOrders, int routes, int waves, long stuckOrders) {
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " 은 양수여야 합니다: " + value);
        }
        return value;
    }
}
