package com.dawnline.fulfillment.adapter.out.messaging;

import com.dawnline.fulfillment.domain.Wave;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code wave.closed.v1} 페이로드 (§4.3, {@code contracts/events/wave.closed.v1.schema.json}).
 *
 * @param waveId      마감된 웨이브
 * @param campId      캠프. 이 이벤트의 {@code partitionKey} 와 같아야 한다 (§4.5)
 * @param serviceTier 티어
 * @param cutoffAt    컷오프
 * @param orderCount  마감 시점의 편입 주문 수. <strong>0 도 유효하다</strong> — 주문이 없는
 *                    캠프의 웨이브도 마감되어야 계획 파이프라인이 정상 종료된다
 * @param closedAt    {@code CLOSING → CLOSED} 전이가 커밋된 시각
 */
public record WaveClosedPayload(
        UUID waveId,
        UUID campId,
        String serviceTier,
        Instant cutoffAt,
        int orderCount,
        Instant closedAt) {

    /** {@code outbox_events.aggregate_type}. */
    public static final String AGGREGATE_TYPE = "wave";

    /** 이벤트 타입 (§4.1). */
    public static final String EVENT_TYPE = "wave.closed";

    /** 페이로드 스키마 major 버전. */
    public static final int SCHEMA_VERSION = 1;

    /**
     * 마감된 웨이브에서 만든다.
     *
     * @param wave 마감된 웨이브
     */
    public static WaveClosedPayload of(Wave wave) {
        Objects.requireNonNull(wave, "wave");
        Instant closedAt = Objects.requireNonNull(wave.closedAt(),
                "마감되지 않은 웨이브로는 wave.closed 를 만들 수 없습니다");
        return new WaveClosedPayload(wave.id(), wave.campId(), wave.serviceTier().name(),
                wave.cutoffAt(), wave.orderCount(), closedAt);
    }
}
