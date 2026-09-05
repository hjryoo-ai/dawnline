package com.dawnline.dispatch.adapter.in.messaging;

import com.dawnline.dispatch.application.port.in.LoadCandidateUseCase;
import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.JsonNode;

/**
 * {@code fulfillment.planned} 수신 → 계획 후보 적재 (§4.1, §5.3).
 *
 * <p>어댑터가 하는 일은 셋뿐이다 — 봉투를 열고, {@link IdempotentConsumer} 로 한 번만 실행하고
 * (불변규칙 2), 유스케이스를 부른다.
 *
 * <h2>{@code UNSERVICEABLE} 은 조용히 넘긴다</h2>
 * 배차 불가는 fulfillment 가 이미 내린 정상적인 판정이고 order-service 가 주문을 종결한다
 * (§5.2 6단계, Phase 2-5-1). dispatch 에게는 <strong>계획할 것이 없다</strong>는 뜻이므로
 * 거부 카운터를 올리지 않는다 — 그 값은 "이벤트를 처리하지 못했다" 를 세는 것이지
 * "배차할 것이 없었다" 를 세는 것이 아니다.
 */
public class FulfillmentPlannedListener {

    /** {@code Topics.forEvent("fulfillment.planned", 1)} 와 같아야 한다. 테스트가 확인한다. */
    static final String FULFILLMENT_PLANNED_TOPIC = "dawnline.fulfillment.planned.v1";

    /** {@code processed_events.consumer} 값 (§8.5). 인스턴스마다 달라지면 멱등이 깨진다. */
    static final String CONSUMER = "dispatch-service";

    private static final Logger log = LoggerFactory.getLogger(FulfillmentPlannedListener.class);

    private final IdempotentConsumer consumer;
    private final LoadCandidateUseCase loadCandidate;
    private final EventJson json;

    /**
     * @param consumer      멱등 게이트 (불변규칙 2)
     * @param loadCandidate 적재 유스케이스
     * @param json          봉투 역직렬화
     */
    public FulfillmentPlannedListener(IdempotentConsumer consumer,
            LoadCandidateUseCase loadCandidate, EventJson json) {

        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.loadCandidate = Objects.requireNonNull(loadCandidate, "loadCandidate");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * @param record 브로커 레코드
     */
    @KafkaListener(topics = FULFILLMENT_PLANNED_TOPIC, groupId = CONSUMER)
    public void onFulfillmentPlanned(ConsumerRecord<String, String> record) {
        EventEnvelope<JsonNode> envelope = json.readEnvelope(record.value());
        JsonNode payload = envelope.payload();

        if (!FulfillmentPlannedPayload.isCandidate(payload)) {
            // 배차 불가는 정상 판정이다. 멱등 기록도 남기지 않는다 — 처리할 일이 없었다.
            log.debug("계획 후보가 아닌 fulfillment.planned 입니다: eventId={}", envelope.eventId());
            return;
        }

        consumer.runOnce(envelope, CONSUMER,
                () -> loadCandidate.load(FulfillmentPlannedPayload.toSnapshot(payload)));
    }
}
