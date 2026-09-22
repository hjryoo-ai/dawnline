package com.dawnline.dispatch.adapter.in.messaging;

import com.dawnline.dispatch.application.DispatchMetrics;
import com.dawnline.dispatch.application.port.in.RecordDeliveryStatusUseCase;
import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import java.util.Objects;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.JsonNode;

/**
 * {@code delivery.status} 수신 → {@code route_stops.status} 전이 (§4.1, ADR-047).
 *
 * <p>어댑터가 하는 일은 넷이다 — 봉투를 열고, {@link IdempotentConsumer} 로 한 번만 실행하고
 * (불변규칙 2), 계약이 모르는 상태를 거르고, 유스케이스를 부른다.
 *
 * <h2>모르는 {@code status} 는 DLQ 가 아니다</h2>
 * §4.7 이 같은 major 안에서 enum 값 추가를 허용하므로, 모르는 값은 발행자의 잘못이 아니라
 * <em>이쪽이 아직 모르는 것</em>이다. 그 이벤트를 DLQ 에 쌓으면 사람이 봐도 할 일이 없고 진짜
 * 장애가 그 안에 묻힌다. 무시하되 <strong>센다</strong> — 조용히 넘어가면 「새 상태값이 배포됐고
 * dispatch 만 모른다」가 어디에도 나타나지 않는다.
 *
 * <p>세는 자리가 {@code dawnline_event_rejected_total} 인 이유: 이것은 순서 뒤바뀜이 아니라
 * <strong>사람이 봐야 하는 상황</strong>이다(§4.6 3행). stale 과 섞으면 「늘 조금씩 있는 값」에
 * 묻힌다.
 */
public class DeliveryStatusListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryStatusListener.class);

    /** {@code Topics.forEvent("delivery.status", 1)} 와 같아야 한다. 테스트가 확인한다. */
    static final String DELIVERY_STATUS_TOPIC = "dawnline.delivery.status.v1";

    /** {@code processed_events.consumer} 값 (§8.5). 다른 리스너들과 같은 값이다. */
    static final String CONSUMER = "dispatch-service";

    private final IdempotentConsumer consumer;
    private final RecordDeliveryStatusUseCase recordStatus;
    private final EventJson json;
    private final DispatchMetrics metrics;

    /**
     * @param consumer     멱등 게이트 (불변규칙 2)
     * @param recordStatus 전이 유스케이스
     * @param json         봉투 역직렬화
     * @param metrics      §9.1 메트릭
     */
    public DeliveryStatusListener(IdempotentConsumer consumer,
            RecordDeliveryStatusUseCase recordStatus, EventJson json, DispatchMetrics metrics) {

        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.recordStatus = Objects.requireNonNull(recordStatus, "recordStatus");
        this.json = Objects.requireNonNull(json, "json");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * @param record 브로커 레코드
     */
    @KafkaListener(topics = DELIVERY_STATUS_TOPIC, groupId = CONSUMER)
    public void onDeliveryStatus(ConsumerRecord<String, String> record) {
        EventEnvelope<JsonNode> envelope = json.readEnvelope(record.value());
        JsonNode payload = envelope.payload();

        consumer.runOnce(envelope, CONSUMER, () -> {
            Optional<RecordDeliveryStatusUseCase.DeliveryStatusCommand> command =
                    DeliveryStatusPayload.toCommand(payload);
            if (command.isEmpty()) {
                log.warn("모르는 배송 상태값이다. eventId={}", envelope.eventId());
                metrics.deliveryStatusUnknown();
                return;
            }
            recordStatus.record(command.get());
        });
    }
}
