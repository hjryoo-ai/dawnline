package com.dawnline.fulfillment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code dawnline.fulfillment.*} 설정 (DESIGN.md §5.2, ADR-020·023).
 *
 * @param wave      웨이브 마감 grace 와 지각 도착 상한
 * @param retention 보존 정리 (ADR-023)
 */
@ConfigurationProperties(prefix = "dawnline.fulfillment")
public record FulfillmentProperties(
        @DefaultValue Wave wave,
        @DefaultValue Retention retention) {

    /**
     * 웨이브 시간 설정 ([ADR-020](docs/adr/ADR-020-cutoff-ownership-wave-grace-promise-revision.md)).
     *
     * @param grace           마감 여유. {@code cutoffAt + grace} 에 마감한다. 기본 90초는 outbox
     *                        릴레이 폴링(100ms) + 컨슈머 지연 예산(§8.1)에서 나온 값이고, 스케줄러
     *                        주기 30초는 마감을 늦추는 쪽으로만 작용하므로 예산이 아니라 여유다
     * @param stalePlacedAfter 지각 도착 상한. {@code cutoffAt < now − 이 값}이면 다음 웨이브가
     *                        아니라 {@code UNSERVICEABLE(STALE_PLACED)} 다 (ADR-020 후속 정정).
     *                        <strong>이 값을 늘리면 {@link Retention#orders()} 30일을 함께
     *                        재검토해야 한다</strong> — 30일에 행을 지운 뒤의 방어가 이 상한이다
     *                        (ADR-023 §2-1)
     */
    public record Wave(
            @DefaultValue("90s") Duration grace,
            @DefaultValue("24h") Duration stalePlacedAfter) {

        public Wave {
            requirePositive(grace, "dawnline.fulfillment.wave.grace");
            requirePositive(stalePlacedAfter, "dawnline.fulfillment.wave.stale-placed-after");
        }
    }

    /**
     * 보존 정리 ([ADR-023](docs/adr/ADR-023-fulfillment-retention.md)).
     *
     * @param enabled          정리 활성화. 끄면 표가 자라기만 하므로, 여러 인스턴스 중 하나만
     *                         돌리고 싶을 때가 아니면 켜 둔다
     * @param orders           {@code fulfillment_orders} 보존 (기본 30일, {@code updated_at} 기준).
     *                         DLQ 보존(§7.3)과 <em>같은 창</em>이며 DLQ 를 바꾸면 함께 바꾼다
     * @param waves            {@code waves} 보존 (기본 90일, {@code closed_at} 기준)
     * @param batchSize        한 트랜잭션에서 지울 최대 행 수
     * @param maxBatchesPerRun 한 실행에서 반복할 최대 배치 수. 상한에 걸리면 남은 행은 다음 실행이
     *                         지운다 — 정리는 정확성이 아니라 용량 관리라 밀려도 안전하다
     */
    public record Retention(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("30d") Duration orders,
            @DefaultValue("90d") Duration waves,
            @DefaultValue("1000") int batchSize,
            @DefaultValue("200") int maxBatchesPerRun) {

        public Retention {
            requirePositive(orders, "dawnline.fulfillment.retention.orders");
            requirePositive(waves, "dawnline.fulfillment.retention.waves");
            if (batchSize < 1) {
                throw new IllegalArgumentException("dawnline.fulfillment.retention.batch-size 는 1 이상이어야 합니다");
            }
            if (maxBatchesPerRun < 1) {
                throw new IllegalArgumentException(
                        "dawnline.fulfillment.retention.max-batches-per-run 은 1 이상이어야 합니다");
            }
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " 은 양수여야 합니다: " + value);
        }
    }
}
