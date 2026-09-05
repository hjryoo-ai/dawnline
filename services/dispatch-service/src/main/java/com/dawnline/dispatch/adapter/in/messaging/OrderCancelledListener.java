package com.dawnline.dispatch.adapter.in.messaging;

import com.dawnline.dispatch.application.port.in.CancelOrderUseCase;
import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.JsonNode;

/**
 * {@code order.cancelled} 수신 → 취소 반영 (§4.1, §6.10).
 *
 * <p>어댑터가 하는 일은 셋뿐이다 — 봉투를 열고, {@link IdempotentConsumer} 로 한 번만 실행하고
 * (불변규칙 2), 유스케이스를 부른다.
 *
 * <h2>거부도 DLQ 가 아니다</h2>
 * 배송이 끝난 뒤 도착한 취소는 <strong>재처리해도 같은 결과</strong>다(§4.6). DLQ 에 쌓으면 진짜
 * 장애가 그 안에 묻힌다. 그래서 유스케이스가 {@code TOO_LATE} 를 돌려주고 예외를 던지지 않으며,
 * 그 사실은 {@code dawnline_cancel_too_late_total} 로 보인다.
 */
public class OrderCancelledListener {

    /** {@code Topics.forEvent("order.cancelled", 1)} 와 같아야 한다. 테스트가 확인한다. */
    static final String ORDER_CANCELLED_TOPIC = "dawnline.order.cancelled.v1";

    /** {@code processed_events.consumer} 값 (§8.5). {@code fulfillment.planned} 와 같은 값이다. */
    static final String CONSUMER = "dispatch-service";

    private final IdempotentConsumer consumer;
    private final CancelOrderUseCase cancelOrder;
    private final EventJson json;

    /**
     * @param consumer    멱등 게이트 (불변규칙 2)
     * @param cancelOrder 취소 유스케이스
     * @param json        봉투 역직렬화
     */
    public OrderCancelledListener(IdempotentConsumer consumer, CancelOrderUseCase cancelOrder,
            EventJson json) {

        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.cancelOrder = Objects.requireNonNull(cancelOrder, "cancelOrder");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * @param record 브로커 레코드
     */
    @KafkaListener(topics = ORDER_CANCELLED_TOPIC, groupId = CONSUMER)
    public void onOrderCancelled(ConsumerRecord<String, String> record) {
        EventEnvelope<JsonNode> envelope = json.readEnvelope(record.value());
        JsonNode payload = envelope.payload();

        consumer.runOnce(envelope, CONSUMER, () -> cancelOrder.cancel(
                OrderCancelledPayload.orderId(payload),
                OrderCancelledPayload.cancelledAt(payload)));
    }
}
