package com.dawnline.tracking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code dawnline.tracking.*} 설정 (DESIGN.md §5.4).
 *
 * <p>{@code ignoreUnknownFields = false} — 모르는 키가 있으면 기동에서 실패한다. 없어진 스위치를
 * 켜 두고 켜졌다고 믿는 것이 {@code libs/messaging} 에서 한 번 있었던 일이다.
 *
 * @param partitions {@code shipment_events} 일 파티션 관리
 */
@ConfigurationProperties(prefix = "dawnline.tracking", ignoreUnknownFields = false)
public record TrackingProperties(@DefaultValue Partitions partitions) {

    /**
     * 일 파티션 관리 (§5.4, §7.1 보존 30일).
     *
     * <p>{@code intervalMs}·{@code initialDelayMs} 는 이 레코드에서 읽지 않는다.
     * {@code ShipmentEventPartitions} 의 {@code @Scheduled} 가 속성 플레이스홀더로 직접 읽기
     * 때문이고, 여기에 두는 것은 설정 메타데이터(IDE 자동완성·문서)를 위해서다 — 기본값이
     * 어긋나지 않는지는 {@code ScheduledDefaultsTest} 가 본다.
     *
     * @param enabled         파티션 스케줄러 활성화. 끄면 <strong>생성이 멈춘다</strong> —
     *                        테스트에서 직접 호출할 때만 끈다
     * @param aheadDays       오늘로부터 앞으로 덮어 둘 일수. 기본 7 은 마이그레이션의 부트스트랩
     *                        창(어제 + 오늘 + 7)과 같은 값이다
     * @param retentionDays   보존 일수 (§5.4 기본 30). {@code aheadDays} 보다 커야 한다
     * @param intervalMs      실행 간격(ms). 기본 1시간
     * @param initialDelayMs  기동 후 첫 실행까지 지연(ms). 기본 0 — 재기동 시점에는 마이그레이션이
     *                        만든 창이 이미 지났을 수 있다
     */
    public record Partitions(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("7") int aheadDays,
            @DefaultValue("30") int retentionDays,
            @DefaultValue("3600000") long intervalMs,
            @DefaultValue("0") long initialDelayMs) {

        public Partitions {
            if (aheadDays < 1) {
                throw new IllegalArgumentException("dawnline.tracking.partitions.ahead-days 는 1 이상이어야 합니다");
            }
            if (retentionDays < 1) {
                throw new IllegalArgumentException(
                        "dawnline.tracking.partitions.retention-days 는 1 이상이어야 합니다");
            }
        }
    }
}
