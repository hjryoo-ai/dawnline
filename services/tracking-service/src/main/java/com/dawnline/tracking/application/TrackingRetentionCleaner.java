package com.dawnline.tracking.application;

import com.dawnline.messaging.retention.RetentionAges;
import com.dawnline.tracking.application.port.out.TrackingRetention;
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
 * {@code shipments} 종결 30일 · 상한 365일 · {@code route_revisions} 90일 보존 정리 — 일 1회
 * ([ADR-058](docs/adr/ADR-058-shipment-and-read-model-retention.md), DESIGN.md §5.4 「보존」).
 *
 * <h2>30일에 지워도 되살아나지 않는 이유</h2>
 * 배송 행을 만드는 사실은 {@code route.assigned} 하나이고, 그것은 배송이 <em>종결되기 전에</em> 발행된다. 그래서
 * 그 사실이 DLQ 에 들어갔다면 DLQ 보존(30일)이 먼저 끝난다 — 종결 + 30일에 지울 때 그 레코드는 이미 없다.
 * 등호라 여유가 없고, 그래서 둘째 방어가 있다: {@code route_revisions} 는 90일이고 <strong>참조하는 배송이 없을
 * 때만</strong> 지워진다. 재처리된 개정은 그 행의 번호 비교(ADR-045)에 막힌다.
 *
 * <h2>순서가 있다: 배송 먼저, 개정 나중</h2>
 * 개정의 가드({@code NOT EXISTS shipments})가 배송이 남아 있는 라우트를 지킨다. 30일과 90일이라는 두 기간이
 * 순서를 자연히 만족시키지만 그것은 고른 결과이지 강제되는 성질이 아니다 — 그래서 쿼리에 가드를 두고,
 * 개정 보존이 배송 보존보다 짧은 설정은 기동에서 거부한다.
 *
 * <h2>비종결 배송은 상한까지 남는다</h2>
 * 걸린 배송은 조사 대상이라 30일에 지우지 않는다. 365일 상한은 <strong>정리이지 정책이 아니다</strong> — 표가
 * 무한히 자라지 않게 할 뿐이다. 그 수는 같은 주문의 {@code rm_orders} 비종결 행이 ops-api 에서 센다
 * ({@code dawnline_rm_orders_stuck}) — 배송 결과는 이 서비스의 {@code delivery.status} 로만 가기 때문이다.
 *
 * <h2>실패는 삼키되 보이게</h2>
 * 표마다 끝까지 돈 정리만 {@code dawnline_retention_last_success_age_seconds{table}} 을 0 으로 되돌린다(ADR-058
 * 결정 6). 배송 쪽이 실패하면 개정 쪽은 돌지 않으므로 두 표의 나이가 함께 자란다.
 */
public class TrackingRetentionCleaner {

    private static final Logger log = LoggerFactory.getLogger(TrackingRetentionCleaner.class);

    private final TrackingRetention retention;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration shipments;
    private final Duration shipmentsCap;
    private final Duration routeRevisions;
    private final int batchSize;
    private final int maxBatchesPerRun;
    private final RetentionAges.Table shipmentAge;
    private final RetentionAges.Table revisionAge;

    /**
     * @param retention          삭제 포트
     * @param transactionManager 배치마다 새 트랜잭션을 여는 데 쓴다
     * @param clock              임계 시각 계산 (불변규칙 12)
     * @param shipments          종결 배송 보존 (기본 30일)
     * @param shipmentsCap       비종결 배송까지 지우는 상한 (기본 365일)
     * @param routeRevisions     개정 보존 (기본 90일)
     * @param batchSize          한 트랜잭션에서 지울 최대 행 수
     * @param maxBatchesPerRun   한 번의 실행에서 반복할 최대 배치 수 (단계마다 각각)
     * @param ages               성공 나이 게이지 — 생성하면서 두 표를 등록한다
     */
    public TrackingRetentionCleaner(TrackingRetention retention, PlatformTransactionManager transactionManager,
            Clock clock, Duration shipments, Duration shipmentsCap, Duration routeRevisions, int batchSize,
            int maxBatchesPerRun, RetentionAges ages) {

        this.retention = Objects.requireNonNull(retention, "retention");
        Objects.requireNonNull(transactionManager, "transactionManager");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.shipments = requirePositive(shipments, "shipments");
        this.shipmentsCap = requirePositive(shipmentsCap, "shipmentsCap");
        this.routeRevisions = requirePositive(routeRevisions, "routeRevisions");
        if (shipments.compareTo(shipmentsCap) > 0) {
            throw new IllegalArgumentException(
                    "배송 보존(%s)이 상한(%s)보다 길 수 없습니다 — 상한이 종결 배송을 먼저 지웁니다"
                            .formatted(shipments, shipmentsCap));
        }
        if (shipments.compareTo(routeRevisions) > 0) {
            // 개정이 배송보다 먼저 만료되면 개정 삭제가 매번 NOT EXISTS 에 막히고, 등호인 30일의 둘째 방어가
            // 설정 하나로 사라진다. 조용히 도는 것보다 기동 실패가 낫다.
            throw new IllegalArgumentException(
                    "배송 보존(%s)이 개정 보존(%s)보다 길 수 없습니다 — 개정이 배송의 둘째 방어입니다"
                            .formatted(shipments, routeRevisions));
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
        this.shipmentAge = ages.table("shipments");
        this.revisionAge = ages.table("route_revisions");
    }

    /**
     * 일 1회 정리.
     *
     * <p>초기 지연 기본값 15분은 outbox 정리(1분)·{@code processed_events} 정리(5분)와 <strong>어긋나게</strong>
     * 둔 것이다. 셋 다 같은 스케줄러 풀(4)을 쓴다.
     *
     * <p>예외를 삼킨다. 정리 실패는 용량 문제지 정확성 문제가 아니므로 다음 실행이 이어받는다 — 그리고 삼킨 실패는
     * 성공 나이 게이지가 말한다.
     */
    @Scheduled(
            fixedDelayString = "${dawnline.tracking.retention.cleanup-interval-ms:86400000}",
            initialDelayString = "${dawnline.tracking.retention.cleanup-initial-delay-ms:900000}")
    public void cleanupExpired() {
        try {
            deleteExpired();
        } catch (RuntimeException e) {
            log.warn("tracking 보존 정리 실패. 다음 실행에서 이어서 지웁니다.", e);
        }
    }

    /**
     * 만료 행을 배치로 지운다. 스케줄과 무관하게 직접 호출할 수 있다(테스트·운영 수동 실행).
     *
     * @return 이번 실행에서 삭제된 행 수
     */
    public Deleted deleteExpired() {
        Instant now = clock.instant();
        int settled = deleteInBatches("shipments(종결)", now.minus(shipments),
                retention::deleteSettledShipmentsUpdatedBefore);
        int capped = deleteInBatches("shipments(상한)", now.minus(shipmentsCap),
                retention::deleteShipmentsUpdatedBefore);
        shipmentAge.succeeded();
        // 순서가 중요하다. 배송이 먼저 사라져야 그 라우트의 개정이 가드를 넘는다.
        int revisions = deleteInBatches("route_revisions", now.minus(routeRevisions),
                retention::deleteUnreferencedRevisionsAppliedBefore);
        revisionAge.succeeded();
        return new Deleted(settled, capped, revisions);
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
     * 한 실행의 삭제 결과.
     *
     * @param settledShipments 종결이라 지운 배송
     * @param cappedShipments  상한에 걸려 지운 배송 (비종결 포함)
     * @param routeRevisions   지운 개정
     */
    public record Deleted(int settledShipments, int cappedShipments, int routeRevisions) {
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " 은 양수여야 합니다: " + value);
        }
        return value;
    }
}
