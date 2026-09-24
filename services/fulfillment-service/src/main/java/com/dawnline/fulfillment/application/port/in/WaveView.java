package com.dawnline.fulfillment.application.port.in;

import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.Wave;
import com.dawnline.fulfillment.domain.WaveCloseCause;
import com.dawnline.fulfillment.domain.WaveStatus;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 웨이브 한 행의 읽기 표현 (§5.2).
 *
 * @param waveId      웨이브
 * @param campId      캠프
 * @param serviceTier 티어
 * @param cutoffAt    컷오프 — order-service 가 정한 값 그대로 (ADR-020)
 * @param status      상태
 * @param orderCount  편입 주문 수 — <strong>마감 전에는 0 이다</strong> (ADR-025)
 * @param closedAt    마감 시각
 * @param closeCause  누가 닫았는가 (ADR-054)
 */
public record WaveView(UUID waveId, UUID campId, ServiceTier serviceTier, Instant cutoffAt, WaveStatus status,
        int orderCount, @Nullable Instant closedAt, @Nullable WaveCloseCause closeCause) {

    /**
     * @param wave 도메인 웨이브
     * @return 읽기 표현
     */
    public static WaveView of(Wave wave) {
        return new WaveView(wave.id(), wave.campId(), wave.serviceTier(), wave.cutoffAt(), wave.status(),
                wave.orderCount(), wave.closedAt(), wave.closeCause());
    }
}
