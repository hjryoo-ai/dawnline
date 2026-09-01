package com.dawnline.messaging.idempotency;

import com.dawnline.messaging.MessagingMetrics;

/**
 * 멱등 소비 한 번의 결과 (DESIGN.md §9.1 의 {@code outcome} 라벨).
 *
 * <p>{@code dlq} 는 여기에 없다. DLQ 는 리스너 <em>바깥</em>(에러 핸들러)에서 결정되는 결과라
 * {@link IdempotentConsumer} 가 관측할 수 없다. 그 값은
 * {@code com.dawnline.messaging.kafka.DlqRecordRecoverer} 가 기록한다.
 */
public enum ConsumeOutcome {

    /** 처음 받은 이벤트이고 비즈니스 로직이 끝까지 실행됐다. */
    PROCESSED(MessagingMetrics.OUTCOME_OK),

    /** 이미 처리한 이벤트라 건너뛰었다 (§8.5 "모든 Kafka 리스너: eventId + consumer"). */
    DUPLICATE(MessagingMetrics.OUTCOME_DUP),

    /** 비즈니스 규칙 위반이라 무시했다. DLQ 아님 (§4.6). */
    REJECTED(MessagingMetrics.OUTCOME_REJECTED);

    private final String tag;

    ConsumeOutcome(String tag) {
        this.tag = tag;
    }

    /** {@code outcome} 메트릭 태그 값. */
    public String tag() {
        return tag;
    }
}
