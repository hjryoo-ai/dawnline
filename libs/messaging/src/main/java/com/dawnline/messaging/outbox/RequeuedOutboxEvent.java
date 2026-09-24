package com.dawnline.messaging.outbox;

import java.util.Objects;
import java.util.UUID;

/**
 * 격리를 푼 행 (DESIGN.md §4.6 「격리 조회·재큐 엔드포인트」).
 *
 * <p>풀린 행은 발행 대기다 — 릴레이가 다음 폴링에 집는다. 원인이 남아 있으면 다시 격리된다.
 *
 * @param id            행 id ({@code eventId})
 * @param aggregateType 애그리거트 타입
 * @param aggregateId   애그리거트 id
 * @param eventType     이벤트 타입
 * @param topic         발행 대상 토픽
 */
public record RequeuedOutboxEvent(UUID id, String aggregateType, UUID aggregateId, String eventType, String topic) {

    public RequeuedOutboxEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(topic, "topic");
    }

    /**
     * @param row 풀린 행
     * @return 응답 표현
     */
    public static RequeuedOutboxEvent of(OutboxEvent row) {
        return new RequeuedOutboxEvent(row.id(), row.aggregateType(), row.aggregateId(), row.eventType(), row.topic());
    }
}
