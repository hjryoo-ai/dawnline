package com.dawnline.fulfillment.adapter.in.messaging;

import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.MessagingMetrics;
import com.dawnline.messaging.idempotency.EventRejectedException;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.messaging.kafka.EventRecords;
import com.dawnline.fulfillment.application.port.in.CancelFulfillmentOrderUseCase;
import com.dawnline.fulfillment.application.port.in.PlanOrderUseCase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * 주문 이벤트 수신 (§4.1, §5.2).
 *
 * <p>어댑터가 하는 일은 셋뿐이다 — 봉투를 열고, {@link IdempotentConsumer} 로 한 번만 실행하고
 * (불변규칙 2), 유스케이스의 판정을 메트릭·예외로 번역한다. 판정도 편입도 여기 없다.
 *
 * <h2>{@code UNSERVICEABLE} 은 거부가 아니다</h2>
 * 배차 불가는 <strong>정상적인 판정 결과</strong>이고 {@code fulfillment.planned} 로 하류에
 * 나간다(§5.2 6단계). {@code dawnline_event_rejected_total} 을 올리지 않는다 — 그 카운터는
 * "이벤트를 처리하지 못했다" 를 세는 값이지 "주문을 배차하지 못했다" 를 세는 값이 아니다.
 *
 * <h2>토픽 이름을 리터럴로 적는 이유</h2>
 * {@code @KafkaListener} 의 {@code topics} 는 컴파일 타임 상수여야 해서 {@code Topics.forEvent}
 * 를 부를 수 없다. 두 값이 어긋나지 않도록 {@code OrderEventListenerTest} 가 규칙(§4.1)으로 만든
 * 이름과 대조한다.
 */
public class OrderEventListener {

    /** {@code Topics.forEvent("order.placed", 1)} 와 같아야 한다. 테스트가 확인한다. */
    static final String ORDER_PLACED_TOPIC = "dawnline.order.placed.v1";

    /** {@code Topics.forEvent("order.cancelled", 1)} 와 같아야 한다. */
    static final String ORDER_CANCELLED_TOPIC = "dawnline.order.cancelled.v1";

    /** {@code processed_events.consumer} 값 (§8.5). 인스턴스마다 달라지면 멱등이 깨진다. */
    static final String CONSUMER = "fulfillment-service";

    /** 취소 선착 뒤에 온 {@code order.placed} 의 거부 사유 (ADR-022). */
    static final String CANCELLED_BEFORE_PLACED = "cancelled_before_placed";

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final IdempotentConsumer consumer;
    private final PlanOrderUseCase planOrder;
    private final CancelFulfillmentOrderUseCase cancelOrder;
    private final EventJson json;
    private final MeterRegistry meters;

    /**
     * @param consumer    멱등 게이트 (불변규칙 2)
     * @param planOrder   계획 유스케이스
     * @param cancelOrder 취소 유스케이스
     * @param json        이벤트 JSON 코덱
     * @param meters      Micrometer 레지스트리 (§9.1)
     */
    public OrderEventListener(IdempotentConsumer consumer, PlanOrderUseCase planOrder,
            CancelFulfillmentOrderUseCase cancelOrder, EventJson json, MeterRegistry meters) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.planOrder = Objects.requireNonNull(planOrder, "planOrder");
        this.cancelOrder = Objects.requireNonNull(cancelOrder, "cancelOrder");
        this.json = Objects.requireNonNull(json, "json");
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    /**
     * {@code order.placed} — 주문 접수 (§4.1).
     *
     * @param record Kafka 레코드
     */
    @KafkaListener(topics = ORDER_PLACED_TOPIC)
    public void onOrderPlaced(ConsumerRecord<String, String> record) {
        EventEnvelope<OrderPlacedPayload> envelope =
                EventRecords.parse(json, record, OrderPlacedPayload.class);

        consumer.consumeOnce(envelope, CONSUMER, () -> {
            PlanOrderUseCase.PlanOutcome outcome =
                    planOrder.plan(envelope.payload().toSnapshot(), envelope.eventId());
            if (outcome.kind() == PlanOrderUseCase.PlanOutcome.Kind.IGNORED) {
                // 이미 판정된 주문이다 — 취소 선착이 대표적이다. 아무 상태도 바꾸지 않았으므로
                // 여기서 던져도 계약을 지킨다(트랜잭션은 커밋된다, §4.6).
                throw new EventRejectedException(CANCELLED_BEFORE_PLACED,
                        "이미 판정된 주문입니다. orderId=" + envelope.payload().orderId());
            }
            log.debug("주문 계획 완료. orderId={}, outcome={}, waveId={}",
                    envelope.payload().orderId(), outcome.kind(), outcome.waveId().orElse(null));
        });
    }

    /**
     * {@code order.cancelled} — 취소 (§4.1).
     *
     * @param record Kafka 레코드
     */
    @KafkaListener(topics = ORDER_CANCELLED_TOPIC)
    public void onOrderCancelled(ConsumerRecord<String, String> record) {
        EventEnvelope<OrderCancelledPayload> envelope =
                EventRecords.parse(json, record, OrderCancelledPayload.class);
        OrderCancelledPayload payload = envelope.payload();

        consumer.consumeOnce(envelope, CONSUMER, () -> {
            CancelFulfillmentOrderUseCase.CancelOutcome outcome =
                    cancelOrder.cancel(payload.orderId(), payload.cancelledAt());
            if (outcome == CancelFulfillmentOrderUseCase.CancelOutcome.CANCELLED_BEFORE_PLACED) {
                // 순서 뒤바뀜을 흡수했다는 사실은 세어 둔다 — 늘어나면 어딘가 지연이 커졌다는 뜻이다.
                countAbsorbed();
            }
        });
    }

    private void countAbsorbed() {
        Counter.builder(MessagingMetrics.EVENT_STALE)
                .description("order.placed 보다 먼저 도착한 order.cancelled (§4.5 순서 뒤바뀜)")
                .tag(MessagingMetrics.TAG_CONSUMER, CONSUMER)
                .tag(MessagingMetrics.TAG_EVENT_TYPE, "order.cancelled")
                .register(meters)
                .increment();
    }
}
