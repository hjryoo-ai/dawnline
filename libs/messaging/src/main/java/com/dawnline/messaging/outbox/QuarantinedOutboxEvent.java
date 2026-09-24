package com.dawnline.messaging.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 격리된 outbox 행의 목록 표현 (DESIGN.md §4.6 「격리 조회·재큐 엔드포인트」, ADR-015 후속 정정).
 *
 * <p><strong>{@code payload}·{@code headers}·{@code partition_key} 가 없다</strong>(§9.3). 셋 다 주소나 고객
 * 식별자를 담을 수 있고, 이 값은 운영자 화면과 ops-api 의 응답으로 간다. 원인을 고치는 데 행 전체가 필요하면
 * 사람이 DB 에서 본다(RB-05 1.2).
 *
 * @param id              행 id — 봉투의 {@code eventId}, 재큐의 대상
 * @param aggregateType   애그리거트 타입
 * @param aggregateId     애그리거트 id
 * @param eventType       이벤트 타입
 * @param topic           발행 대상 토픽
 * @param createdAt       도메인 사건 시각
 * @param failedAt        격리 시각
 * @param publishAttempts 시도 횟수 — 재큐 뒤에 다시 오르면 원인이 남아 있다
 */
public record QuarantinedOutboxEvent(UUID id, String aggregateType, UUID aggregateId, String eventType, String topic,
        Instant createdAt, Instant failedAt, int publishAttempts) {

    public QuarantinedOutboxEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(failedAt, "failedAt");
    }

    /**
     * @param row 격리된 행
     * @return 목록 표현
     * @throws IllegalArgumentException 격리된 행이 아닐 때
     */
    public static QuarantinedOutboxEvent of(OutboxEvent row) {
        Instant failedAt = row.failedAt().orElseThrow(() ->
                new IllegalArgumentException("격리된 행이 아닙니다: id=" + row.id()));
        return new QuarantinedOutboxEvent(row.id(), row.aggregateType(), row.aggregateId(), row.eventType(),
                row.topic(), row.createdAt(), failedAt, row.publishAttempts());
    }
}
