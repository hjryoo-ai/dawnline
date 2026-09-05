package com.dawnline.order.adapter.in.messaging;

import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.MessagingMetrics;
import com.dawnline.messaging.idempotency.EventRejectedException;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.messaging.kafka.EventRecords;
import com.dawnline.order.application.port.in.AdvanceOrderUseCase;
import com.dawnline.order.application.port.in.ApplyFulfillmentPlanUseCase;
import com.dawnline.order.application.port.in.OrderProgress;
import com.dawnline.order.domain.OrderStatus;
import com.dawnline.order.domain.PromisedWindow;
import com.dawnline.order.domain.ServiceTier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * 배송 진행 이벤트 수신 (DESIGN.md §5.1, §4.5, ADR-017).
 *
 * <p>인바운드 메시징 어댑터가 하는 일은 셋뿐이다 — 봉투를 열고, {@link IdempotentConsumer} 로
 * 한 번만 실행하고(불변규칙 2), 유스케이스의 판정을 메트릭·예외로 번역한다.
 * 순서 뒤바뀜 판정은 여기 없다. 그것은 상태 머신의 일이다.
 *
 * <h2>{@code EventRejectedException} 을 언제 던지는가</h2>
 * <strong>이 이벤트가 아무 상태도 바꾸지 않았을 때만</strong> 던진다. 그 예외는 트랜잭션을
 * 커밋시키므로(§4.6), 일부만 적용된 상태에서 던지면 "거부됐다" 는 기록과 실제로 바뀐 상태가
 * 어긋난다. {@code delivery.status} 는 한 stop 에 여러 주문이 묶이므로(§6.2) 결과가 섞일 수 있고,
 * 그때는 던지지 않고 주문별로 세기만 한다.
 *
 * <h2>토픽 이름을 리터럴로 적는 이유</h2>
 * {@code @KafkaListener} 의 {@code topics} 는 컴파일 타임 상수여야 해서
 * {@code Topics.forEvent(...)} 를 부를 수 없다. 두 값이 어긋나지 않도록
 * {@code OrderProgressListenerTest} 가 규칙(§4.1)으로 만든 이름과 대조한다.
 */
public class OrderProgressListener {

    /** {@code Topics.forEvent("order.dispatched", 1)} 와 같아야 한다. 테스트가 확인한다. */
    static final String ORDER_DISPATCHED_TOPIC = "dawnline.order.dispatched.v1";

    /** {@code Topics.forEvent("delivery.status", 1)} 와 같아야 한다. */
    static final String DELIVERY_STATUS_TOPIC = "dawnline.delivery.status.v1";

    /** {@code Topics.forEvent("fulfillment.planned", 1)} 와 같아야 한다. */
    static final String FULFILLMENT_PLANNED_TOPIC = "dawnline.fulfillment.planned.v1";

    /** {@code processed_events.consumer} 값 (§8.5). 인스턴스마다 달라지면 멱등이 깨진다. */
    static final String CONSUMER = "order-service";

    private static final Logger log = LoggerFactory.getLogger(OrderProgressListener.class);

    /** {@code delivery.status} 의 status → 주문 상태. {@code ARRIVED} 는 대응하는 상태가 없다. */
    private static final Map<String, OrderStatus> DELIVERY_TARGETS = Map.of(
            DeliveryStatusPayload.COMPLETED, OrderStatus.DELIVERED,
            DeliveryStatusPayload.FAILED, OrderStatus.FAILED);

    private final IdempotentConsumer consumer;
    private final AdvanceOrderUseCase advanceOrder;
    private final ApplyFulfillmentPlanUseCase applyPlan;
    private final EventJson json;
    private final MeterRegistry meters;

    /**
     * @param consumer     멱등 게이트 (불변규칙 2)
     * @param advanceOrder 상태 전이 유스케이스
     * @param applyPlan    계획 반영 유스케이스 (전이 + 데이터 부착, ADR-017 경고)
     * @param json         이벤트 JSON 코덱
     * @param meters       Micrometer 레지스트리 (§9.1)
     */
    public OrderProgressListener(IdempotentConsumer consumer, AdvanceOrderUseCase advanceOrder,
            ApplyFulfillmentPlanUseCase applyPlan, EventJson json, MeterRegistry meters) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.advanceOrder = Objects.requireNonNull(advanceOrder, "advanceOrder");
        this.applyPlan = Objects.requireNonNull(applyPlan, "applyPlan");
        this.json = Objects.requireNonNull(json, "json");
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    /**
     * {@code fulfillment.planned} — FC·캠프·권역·웨이브 결정 결과 (§4.1, §5.2 6단계).
     *
     * <p>두 결과를 모두 받는다. {@code PLANNED} 는 상태 전이(+ 약속 개정)이고,
     * {@code UNSERVICEABLE} 은 주문을 {@code FAILED} 로 두고 사유를 남긴다 — 배차하지 못한 것도
     * 고객에게 답해야 하는 사실이다.
     *
     * @param record Kafka 레코드
     */
    @KafkaListener(topics = FULFILLMENT_PLANNED_TOPIC)
    public void onFulfillmentPlanned(ConsumerRecord<String, String> record) {
        EventEnvelope<FulfillmentPlannedPayload> envelope =
                EventRecords.parse(json, record, FulfillmentPlannedPayload.class);
        FulfillmentPlannedPayload payload = envelope.payload();

        consumer.consumeOnce(envelope, CONSUMER, () -> {
            ApplyFulfillmentPlanUseCase.PlanApplication result = apply(payload, envelope.occurredAt());
            countPlanApplication(envelope.eventType(), result);
            if (result == ApplyFulfillmentPlanUseCase.PlanApplication.REJECTED
                    || result == ApplyFulfillmentPlanUseCase.PlanApplication.ORDER_NOT_FOUND) {
                // 아무것도 바꾸지 않았다. 주문 하나짜리 이벤트라 여기서 던져도 계약을 지킨다.
                throw new EventRejectedException(result.name(),
                        "fulfillment.planned 를 적용할 수 없습니다. orderId=" + payload.orderId());
            }
        });
    }

    private ApplyFulfillmentPlanUseCase.PlanApplication apply(FulfillmentPlannedPayload payload,
            java.time.Instant at) {

        if (FulfillmentPlannedPayload.UNSERVICEABLE.equals(payload.outcome())) {
            String reason = payload.reason() == null ? "UNKNOWN" : payload.reason();
            return applyPlan.unserviceable(payload.orderId(), reason, at);
        }
        // 개정된 창도 그 티어의 길이 상한을 지켜야 한다 — 지키지 못하면 계약이 깨진 것이고,
        // 조용히 받는 것보다 터지는 편이 낫다(DLQ 로 가서 사람이 본다).
        PromisedWindow window = payload.revised()
                ? PromisedWindow.of(payload.promisedWindow().start(), payload.promisedWindow().end(),
                        ServiceTier.valueOf(payload.serviceTier()))
                : null;
        return applyPlan.planned(payload.orderId(), window, payload.revised(), at);
    }

    /**
     * 계획 반영 결과를 센다.
     *
     * <p>{@code STALE_BUT_DATA_APPLIED} 를 {@code STALE} 과 나눠 세는 이유는 ADR-017 의 경고가
     * 가리키는 지점이기 때문이다 — 순서 뒤바뀜에도 <em>불구하고</em> 고객의 약속이 갱신된
     * 경우이고, 그 둘을 묶으면 그 사실이 보이지 않는다.
     */
    private void countPlanApplication(String eventType,
            ApplyFulfillmentPlanUseCase.PlanApplication result) {

        if (result != ApplyFulfillmentPlanUseCase.PlanApplication.STALE
                && result != ApplyFulfillmentPlanUseCase.PlanApplication.STALE_BUT_DATA_APPLIED) {
            return;
        }
        Counter.builder(MessagingMetrics.EVENT_STALE)
                .description("이미 지나온 지점으로의 전이라 무시한 이벤트 (ADR-017)")
                .tag(MessagingMetrics.TAG_CONSUMER, CONSUMER)
                .tag(MessagingMetrics.TAG_EVENT_TYPE, eventType + "." + result.name().toLowerCase())
                .register(meters)
                .increment();
    }

    /**
     * {@code order.dispatched} — 주문이 라우트에 배정됐다 (§4.1).
     *
     * @param record Kafka 레코드
     */
    @KafkaListener(topics = ORDER_DISPATCHED_TOPIC)
    public void onOrderDispatched(ConsumerRecord<String, String> record) {
        EventEnvelope<OrderDispatchedPayload> envelope =
                EventRecords.parse(json, record, OrderDispatchedPayload.class);
        OrderDispatchedPayload payload = envelope.payload();

        consumer.consumeOnce(envelope, CONSUMER, () -> {
            OrderProgress progress = advanceOrder.advance(
                    payload.orderId(), OrderStatus.DISPATCHED, payload.dispatchedAt());
            countStale(envelope.eventType(), progress);
            if (progress.isRejected()) {
                // 주문 하나짜리 이벤트다. 아무것도 바꾸지 않았으므로 여기서 던져도 계약을 지킨다.
                throw new EventRejectedException(progress.name(),
                        "order.dispatched 를 적용할 수 없습니다. orderId=" + payload.orderId());
            }
        });
    }

    /**
     * {@code delivery.status} — 한 stop 의 도착·완료·실패 (§4.1, §6.2).
     *
     * @param record Kafka 레코드
     */
    @KafkaListener(topics = DELIVERY_STATUS_TOPIC)
    public void onDeliveryStatus(ConsumerRecord<String, String> record) {
        EventEnvelope<DeliveryStatusPayload> envelope =
                EventRecords.parse(json, record, DeliveryStatusPayload.class);
        DeliveryStatusPayload payload = envelope.payload();

        consumer.consumeOnce(envelope, CONSUMER, () -> applyToStop(envelope.eventType(), payload));
    }

    private void applyToStop(String eventType, DeliveryStatusPayload payload) {
        OrderStatus target = DELIVERY_TARGETS.get(payload.status());
        if (target == null) {
            // ARRIVED 등. 주문 상태 머신에 대응하는 상태가 없다 — 그래도 커밋해야 다시 오지 않는다.
            log.debug("주문 상태를 바꾸지 않는 배송 상태입니다. status={}, routeId={}, stopSeq={}",
                    payload.status(), payload.routeId(), payload.stopSeq());
            return;
        }

        Map<OrderProgress, Integer> counts = new EnumMap<>(OrderProgress.class);
        for (UUID orderId : payload.orderIds()) {
            OrderProgress progress = advanceOrder.advance(orderId, target, payload.occurredAt());
            counts.merge(progress, 1, Integer::sum);
            countStale(eventType, progress);
            if (progress.isRejected()) {
                // 한 stop 의 다른 주문들은 정상일 수 있다. 여기서 던지면 그것들까지 함께 멈춘다.
                countRejected(eventType, progress);
            }
        }

        boolean changedNothing = counts.getOrDefault(OrderProgress.APPLIED, 0) == 0
                && counts.getOrDefault(OrderProgress.STALE, 0) == 0;
        if (changedNothing && !payload.orderIds().isEmpty()) {
            // 전부 거부됐다 = 상태를 하나도 안 바꿨다. 이때만 소비 결과를 rejected 로 기록한다.
            throw new EventRejectedException(dominantRejection(counts).name(),
                    "delivery.status 의 주문을 모두 적용할 수 없습니다. routeId=" + payload.routeId()
                            + ", stopSeq=" + payload.stopSeq());
        }
    }

    /** 전부 거부됐을 때 대표 사유. 섞여 있으면 더 심각한 쪽(취소된 주문)을 고른다. */
    private static OrderProgress dominantRejection(Map<OrderProgress, Integer> counts) {
        return counts.containsKey(OrderProgress.TRANSITION_NOT_ALLOWED)
                ? OrderProgress.TRANSITION_NOT_ALLOWED
                : OrderProgress.ORDER_NOT_FOUND;
    }

    private void countStale(String eventType, OrderProgress progress) {
        if (progress != OrderProgress.STALE) {
            return;
        }
        Counter.builder(MessagingMetrics.EVENT_STALE)
                .description("이미 지나온 지점으로의 전이라 무시한 이벤트 (ADR-017)")
                .tag(MessagingMetrics.TAG_CONSUMER, CONSUMER)
                .tag(MessagingMetrics.TAG_EVENT_TYPE, eventType)
                .register(meters)
                .increment();
    }

    /**
     * 주문별 거부를 센다. {@code IdempotentConsumer} 가 예외를 잡아 세는 것과 <em>같은 메트릭</em>이며,
     * stop 안의 일부만 거부된 경우에는 예외를 던지지 않으므로 여기서 직접 올린다.
     */
    private void countRejected(String eventType, OrderProgress progress) {
        Counter.builder(MessagingMetrics.EVENT_REJECTED)
                .description("비즈니스 규칙 위반으로 무시한 이벤트 (DLQ 아님)")
                .tag(MessagingMetrics.TAG_CONSUMER, CONSUMER)
                .tag(MessagingMetrics.TAG_EVENT_TYPE, eventType)
                .tag(MessagingMetrics.TAG_REASON, progress.name())
                .register(meters)
                .increment();
    }
}
