package com.dawnline.order.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dawnline.common.Ids;
import com.dawnline.messaging.MessagingMetrics;
import com.dawnline.messaging.Topics;
import com.dawnline.messaging.idempotency.EventRejectedException;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.order.application.port.in.AdvanceOrderUseCase;
import com.dawnline.order.application.port.in.OrderProgress;
import com.dawnline.order.domain.OrderStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * 리스너의 번역 규칙 (DESIGN.md §4.5·§4.6, ADR-017).
 *
 * <p>실제 Kafka 왕복은 {@code OrderProgressListenerIT} 가 본다. 여기서 보는 것은
 * <em>유스케이스의 판정을 무엇으로 번역하는가</em>다 — 어떤 경우에 {@code EventRejectedException}
 * 을 던지고 어떤 경우에 던지지 않는지, 그리고 stale 을 세는지.
 */
@DisplayName("OrderProgressListener — 판정의 번역")
class OrderProgressListenerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-03T05:00:00Z");
    private static final UUID ROUTE_ID = Ids.newId();

    private AdvanceOrderUseCase advanceOrder;
    private com.dawnline.order.application.port.in.ApplyFulfillmentPlanUseCase applyPlan;
    private MeterRegistry meters;
    private OrderProgressListener listener;

    /** 멱등 게이트는 여기서 검증 대상이 아니다. 넘긴 작업을 그대로 실행하는 스텁으로 둔다. */
    private static IdempotentConsumer passThroughConsumer() {
        IdempotentConsumer consumer = mock(IdempotentConsumer.class);
        when(consumer.consumeOnce(any(com.dawnline.messaging.EventEnvelope.class), any(), any()))
                .thenAnswer(invocation -> {
                    invocation.getArgument(2, Runnable.class).run();
                    return com.dawnline.messaging.idempotency.ConsumeOutcome.PROCESSED;
                });
        return consumer;
    }

    @BeforeEach
    void setUp() {
        advanceOrder = mock(AdvanceOrderUseCase.class);
        applyPlan = mock(com.dawnline.order.application.port.in.ApplyFulfillmentPlanUseCase.class);
        meters = new SimpleMeterRegistry();
        listener = new OrderProgressListener(passThroughConsumer(), advanceOrder, applyPlan,
                EventJson.standard(), meters);
    }

    private static ConsumerRecord<String, String> record(String topic, String value) {
        return new ConsumerRecord<>(topic, 0, 0L, "key", value);
    }

    private static ConsumerRecord<String, String> dispatched(UUID orderId) {
        return record(OrderProgressListener.ORDER_DISPATCHED_TOPIC, """
                {"eventId":"%s","eventType":"order.dispatched","schemaVersion":1,
                 "occurredAt":"2026-09-03T05:00:00Z","producer":"dispatch-service","partitionKey":"%s",
                 "payload":{"orderId":"%s","routeId":"%s","dispatchedAt":"2026-09-03T05:00:00Z"}}
                """.formatted(Ids.newId(), orderId, orderId, ROUTE_ID));
    }

    private static ConsumerRecord<String, String> deliveryStatus(String status, UUID... orderIds) {
        StringBuilder ids = new StringBuilder();
        for (UUID id : orderIds) {
            ids.append(ids.isEmpty() ? "" : ",").append('"').append(id).append('"');
        }
        return record(OrderProgressListener.DELIVERY_STATUS_TOPIC, """
                {"eventId":"%s","eventType":"delivery.status","schemaVersion":1,
                 "occurredAt":"2026-09-03T05:00:00Z","producer":"tracking-service","partitionKey":"%s",
                 "payload":{"routeId":"%s","stopSeq":1,"orderIds":[%s],"status":"%s",
                            "occurredAt":"2026-09-03T05:00:00Z"}}
                """.formatted(Ids.newId(), ROUTE_ID, ROUTE_ID, ids, status));
    }

    private double staleCount() {
        var counter = meters.find(MessagingMetrics.EVENT_STALE).counter();
        return counter == null ? 0 : counter.count();
    }

    private double rejectedCount() {
        var counter = meters.find(MessagingMetrics.EVENT_REJECTED).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void 토픽_이름이_4_1_규칙과_같다() {
        // @KafkaListener 의 topics 는 컴파일 타임 상수여야 해서 Topics.forEvent 를 부를 수 없다.
        // 두 값이 어긋나면 리스너가 존재하지 않는 토픽을 구독하고, 그 사실은 조용하다.
        assertThat(OrderProgressListener.ORDER_DISPATCHED_TOPIC)
                .isEqualTo(Topics.forEvent("order.dispatched", 1));
        assertThat(OrderProgressListener.DELIVERY_STATUS_TOPIC)
                .isEqualTo(Topics.forEvent("delivery.status", 1));
    }

    @Test
    void 리스너_메서드가_KafkaListener_를_달고_있다() throws NoSuchMethodException {
        // ArchUnit 규칙 4 는 어노테이션의 위치만 본다 — 사라지는 것은 잡지 못한다.
        assertThat(OrderProgressListener.class.getMethod("onOrderDispatched", ConsumerRecord.class)
                .isAnnotationPresent(KafkaListener.class)).isTrue();
        assertThat(OrderProgressListener.class.getMethod("onDeliveryStatus", ConsumerRecord.class)
                .isAnnotationPresent(KafkaListener.class)).isTrue();
    }

    @Test
    void order_dispatched_는_사건_시각으로_DISPATCHED_로_옮긴다() {
        UUID orderId = Ids.newId();
        when(advanceOrder.advance(any(), any(), any())).thenReturn(OrderProgress.APPLIED);

        listener.onOrderDispatched(dispatched(orderId));

        verify(advanceOrder).advance(orderId, OrderStatus.DISPATCHED, OCCURRED_AT);
    }

    @Test
    void 철_지난_이벤트는_세기만_하고_던지지_않는다() {
        when(advanceOrder.advance(any(), any(), any())).thenReturn(OrderProgress.STALE);

        assertThatCode(() -> listener.onOrderDispatched(dispatched(Ids.newId()))).doesNotThrowAnyException();

        assertThat(staleCount()).isEqualTo(1);
        assertThat(rejectedCount()).isZero();
    }

    @Test
    void 주문_하나짜리_이벤트의_거부는_예외로_올린다() {
        // 아무것도 바꾸지 않았으므로 EventRejectedException 의 계약(상태 변경 전에 던진다)을 지킨다.
        when(advanceOrder.advance(any(), any(), any())).thenReturn(OrderProgress.TRANSITION_NOT_ALLOWED);

        assertThatThrownBy(() -> listener.onOrderDispatched(dispatched(Ids.newId())))
                .isInstanceOf(EventRejectedException.class)
                .hasMessageContaining("order.dispatched");
    }

    @Test
    void delivery_status_COMPLETED_는_DELIVERED_로_옮긴다() {
        UUID orderId = Ids.newId();
        when(advanceOrder.advance(any(), any(), any())).thenReturn(OrderProgress.APPLIED);

        listener.onDeliveryStatus(deliveryStatus("COMPLETED", orderId));

        verify(advanceOrder).advance(orderId, OrderStatus.DELIVERED, OCCURRED_AT);
    }

    @Test
    void delivery_status_FAILED_는_FAILED_로_옮긴다() {
        UUID orderId = Ids.newId();
        when(advanceOrder.advance(any(), any(), any())).thenReturn(OrderProgress.APPLIED);

        listener.onDeliveryStatus(deliveryStatus("FAILED", orderId));

        verify(advanceOrder).advance(orderId, OrderStatus.FAILED, OCCURRED_AT);
    }

    @Test
    void ARRIVED_는_주문_상태를_건드리지_않지만_커밋된다() {
        // 도착은 배송 진행 정보이지 주문 상태가 아니다. 그래도 소비는 해야 다시 오지 않는다.
        assertThatCode(() -> listener.onDeliveryStatus(deliveryStatus("ARRIVED", Ids.newId())))
                .doesNotThrowAnyException();

        verify(advanceOrder, never()).advance(any(), any(), any());
    }

    @Test
    void 한_stop_의_주문들에_각각_적용한다() {
        // §6.2 stop 통합 — 같은 격자의 주문들이 한 stop 으로 묶인다.
        UUID first = Ids.newId();
        UUID second = Ids.newId();
        when(advanceOrder.advance(any(), any(), any())).thenReturn(OrderProgress.APPLIED);

        listener.onDeliveryStatus(deliveryStatus("COMPLETED", first, second));

        verify(advanceOrder).advance(eq(first), eq(OrderStatus.DELIVERED), any());
        verify(advanceOrder).advance(eq(second), eq(OrderStatus.DELIVERED), any());
    }

    @Test
    void 일부만_거부되면_던지지_않고_세기만_한다() {
        // 여기서 던지면 이미 적용된 주문이 있는 채로 "거부됨" 이 기록된다 — 커밋되기 때문이다.
        UUID applied = Ids.newId();
        UUID rejected = Ids.newId();
        when(advanceOrder.advance(eq(applied), any(), any())).thenReturn(OrderProgress.APPLIED);
        when(advanceOrder.advance(eq(rejected), any(), any())).thenReturn(OrderProgress.ORDER_NOT_FOUND);

        assertThatCode(() -> listener.onDeliveryStatus(deliveryStatus("COMPLETED", applied, rejected)))
                .doesNotThrowAnyException();

        assertThat(rejectedCount()).isEqualTo(1);
    }

    @Test
    void 전부_거부되면_예외로_올린다() {
        // 아무 상태도 바꾸지 않았다 = 이 이벤트는 통째로 거부다.
        when(advanceOrder.advance(any(), any(), any())).thenReturn(OrderProgress.TRANSITION_NOT_ALLOWED);

        assertThatThrownBy(() -> listener.onDeliveryStatus(deliveryStatus("COMPLETED", Ids.newId(), Ids.newId())))
                .isInstanceOf(EventRejectedException.class)
                .hasMessageContaining("delivery.status");
    }

    @Test
    void 하나라도_철_지난_것이면_통째로_거부하지_않는다() {
        // stale 은 정상이다. 나머지가 거부됐다고 이벤트 전체를 거부로 기록하면 알림이 시끄러워진다.
        UUID stale = Ids.newId();
        UUID rejected = Ids.newId();
        when(advanceOrder.advance(eq(stale), any(), any())).thenReturn(OrderProgress.STALE);
        when(advanceOrder.advance(eq(rejected), any(), any())).thenReturn(OrderProgress.ORDER_NOT_FOUND);

        assertThatCode(() -> listener.onDeliveryStatus(deliveryStatus("COMPLETED", stale, rejected)))
                .doesNotThrowAnyException();

        assertThat(staleCount()).isEqualTo(1);
        assertThat(rejectedCount()).isEqualTo(1);
    }

    @Test
    void stale_카운터에_소비자와_이벤트_타입_태그가_붙는다() {
        when(advanceOrder.advance(any(), any(), any())).thenReturn(OrderProgress.STALE);

        listener.onDeliveryStatus(deliveryStatus("COMPLETED", Ids.newId()));

        assertThat(meters.find(MessagingMetrics.EVENT_STALE)
                .tag(MessagingMetrics.TAG_CONSUMER, "order-service")
                .tag(MessagingMetrics.TAG_EVENT_TYPE, "delivery.status")
                .counter()).isNotNull();
    }

    @Test
    void null_인자는_거부한다() {
        assertThatThrownBy(() -> new OrderProgressListener(null, advanceOrder, applyPlan, EventJson.standard(), meters))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderProgressListener(passThroughConsumer(), null, applyPlan, EventJson.standard(), meters))
                .isInstanceOf(NullPointerException.class);
    }
}
