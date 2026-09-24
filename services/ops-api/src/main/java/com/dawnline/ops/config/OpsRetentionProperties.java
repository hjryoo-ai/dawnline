package com.dawnline.ops.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 읽기 모델 보존 정리 ([ADR-058](docs/adr/ADR-058-shipment-and-read-model-retention.md), DESIGN.md §7.1 보존 표).
 *
 * <p>네 기간은 §7.1 보존 표의 네 행이다 — {@code RetentionTableDefaultsTest} 가 표와 이 기본값을 대조한다.
 * {@code cleanupIntervalMs}·{@code cleanupInitialDelayMs} 는 이 레코드에서 읽지 않는다 — {@code @Scheduled} 가
 * 플레이스홀더로 읽고, 여기 두는 것은 메타데이터와 {@code ScheduledDefaultsTest} 의 대조를 위해서다.
 *
 * @param enabled               정리 활성화. 끄면 세 표가 자라기만 한다 — 정리 주체를 밖에 둘 때만 끈다
 * @param orders                종결 주문 행의 보존 (기본 90일, {@code updated_at}). 종결은 주문 취소·배차 불가이거나
 *                              배송 결과가 있는 것이고, NULL 은 종결이 아니다
 * @param ordersCap             비종결 행까지 지우는 상한 (기본 365일) — 정리이지 정책이 아니다. 그 전까지
 *                              {@code dawnline_rm_orders_stuck} 이 센다
 * @param routes                {@code rm_routes} 보존 (기본 90일). 참조하는 주문 행이 없을 때만 지운다
 * @param waves                 {@code rm_waves} 보존 (기본 90일). 참조하는 주문 행이 없을 때만 지운다
 * @param batchSize             한 트랜잭션에서 지울 최대 행 수
 * @param maxBatchesPerRun      한 실행에서 단계마다 반복할 최대 배치 수
 * @param cleanupIntervalMs     실행 간격(ms). 기본 24시간
 * @param cleanupInitialDelayMs 기동 후 첫 실행까지(ms). 기본 15분 — outbox 정리(1분)·{@code processed_events}
 *                              정리(5분)와 어긋나게 둔다
 */
@ConfigurationProperties("dawnline.ops.retention")
public record OpsRetentionProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("90d") Duration orders,
        @DefaultValue("365d") Duration ordersCap,
        @DefaultValue("90d") Duration routes,
        @DefaultValue("90d") Duration waves,
        @DefaultValue("1000") int batchSize,
        @DefaultValue("200") int maxBatchesPerRun,
        @DefaultValue("86400000") long cleanupIntervalMs,
        @DefaultValue("900000") long cleanupInitialDelayMs) {

    public OpsRetentionProperties {
        requirePositive(orders, "dawnline.ops.retention.orders");
        requirePositive(ordersCap, "dawnline.ops.retention.orders-cap");
        requirePositive(routes, "dawnline.ops.retention.routes");
        requirePositive(waves, "dawnline.ops.retention.waves");
        if (batchSize < 1) {
            throw new IllegalArgumentException("dawnline.ops.retention.batch-size 는 1 이상이어야 합니다");
        }
        if (maxBatchesPerRun < 1) {
            throw new IllegalArgumentException("dawnline.ops.retention.max-batches-per-run 은 1 이상이어야 합니다");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " 은 양수여야 합니다: " + value);
        }
    }
}
