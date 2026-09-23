package com.dawnline.ops.adapter.in.messaging;

import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.MessagingMetrics;
import com.dawnline.messaging.idempotency.ConsumeOutcome;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.messaging.kafka.EventRecords;
import com.dawnline.ops.application.port.in.ProjectFactUseCase;
import com.dawnline.ops.application.port.in.ProjectFactUseCase.Projection;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * 읽기 모델 프로젝션 — §4.1 의 토픽 <strong>전부</strong>를 받는다 (DESIGN.md §5.5, ADR-051).
 *
 * <p>어댑터가 하는 일은 셋뿐이다 — 봉투를 열고, {@link IdempotentConsumer} 로 한 번만 실행하고
 * (불변규칙 2), 결과를 메트릭으로 번역한다. 판정도 순서도 여기 없다.
 *
 * <h2>멱등과 순서는 다른 것이다</h2>
 * {@code processed_events} 는 <em>같은 이벤트</em>의 재전달만 막고, 서로 다른 토픽 사이의 순서에
 * 대해서는 아무것도 말하지 않는다. 순서는 프로젝터의 판정이 흡수한다(ADR-051) — 그리고
 * {@code ProjectionShuffleIT} 가 그것을 본다.
 *
 * <h2>「아직 안 왔다」는 거부가 아니다</h2>
 * 이 리스너는 {@code EventRejectedException} 을 던지지 않는다. 빠진 사실이 만드는 것은 빈 칸
 * 하나뿐이고(ADR-051 결정 5), {@code dawnline_event_rejected_total} 은 사람이 봐야 하는 상황을 위해
 * 비워 둔다. 세는 것은 역행({@code dawnline_event_stale_total})뿐이다.
 *
 * <h2>stale 은 커밋 뒤에 센다</h2>
 * 미터 레지스트리는 트랜잭션을 모른다. 프로젝터 안에서 올리면 롤백된 반영의 숫자가 남고, 그 차이는
 * 장애 때 가장 커진다(CLAUDE.md). 그래서 결과를 들고 나와 {@code consumeOnce} 가 돌아온 뒤 — 커밋이
 * 끝난 뒤 — 에 센다. {@code ProjectionListenerTest} 가 그 순서를 본다.
 *
 * <h2>토픽 이름을 리터럴로 적는 이유</h2>
 * {@code @KafkaListener} 의 {@code topics} 는 컴파일 타임 상수여야 한다. 그리고 이 리스너가
 * <strong>계약에 있는 토픽 전부</strong>를 구독하는지는 {@code ProjectionTopicsTest} 가 계약
 * 디렉터리에서 역으로 확인한다 — 토픽이 붙으면 여기 한 줄이 붙어야 한다.
 */
public class ProjectionListener {

    /** {@code processed_events.consumer} 값 (§8.5). */
    static final String CONSUMER = "ops-api";

    static final String ORDER_PLACED_TOPIC = "dawnline.order.placed.v1";
    static final String ORDER_CANCELLED_TOPIC = "dawnline.order.cancelled.v1";
    static final String FULFILLMENT_PLANNED_TOPIC = "dawnline.fulfillment.planned.v1";
    static final String WAVE_CLOSED_TOPIC = "dawnline.wave.closed.v1";
    static final String ROUTE_ASSIGNED_TOPIC = "dawnline.route.assigned.v1";
    static final String ORDER_DISPATCHED_TOPIC = "dawnline.order.dispatched.v1";
    static final String PLAN_COMPLETED_TOPIC = "dawnline.plan.completed.v1";
    static final String PLAN_FAILED_TOPIC = "dawnline.plan.failed.v1";
    static final String DELIVERY_STATUS_TOPIC = "dawnline.delivery.status.v1";
    static final String DELIVERY_AT_RISK_TOPIC = "dawnline.delivery.at-risk.v1";
    static final String ROUTE_DEPARTED_TOPIC = "dawnline.delivery.route-departed.v1";

    private final IdempotentConsumer consumer;
    private final ProjectFactUseCase projector;
    private final EventJson json;
    private final MeterRegistry meters;

    /**
     * @param consumer  멱등 게이트 (불변규칙 2)
     * @param projector 프로젝션 유스케이스
     * @param json      이벤트 JSON 코덱
     * @param meters    Micrometer 레지스트리 (§9.1)
     */
    public ProjectionListener(IdempotentConsumer consumer, ProjectFactUseCase projector, EventJson json,
            MeterRegistry meters) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.projector = Objects.requireNonNull(projector, "projector");
        this.json = Objects.requireNonNull(json, "json");
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    @KafkaListener(topics = ORDER_PLACED_TOPIC)
    public void onOrderPlaced(ConsumerRecord<String, String> record) {
        project(record, Payloads.OrderPlaced.class);
    }

    @KafkaListener(topics = ORDER_CANCELLED_TOPIC)
    public void onOrderCancelled(ConsumerRecord<String, String> record) {
        project(record, Payloads.OrderCancelled.class);
    }

    @KafkaListener(topics = FULFILLMENT_PLANNED_TOPIC)
    public void onFulfillmentPlanned(ConsumerRecord<String, String> record) {
        project(record, Payloads.FulfillmentPlanned.class);
    }

    @KafkaListener(topics = WAVE_CLOSED_TOPIC)
    public void onWaveClosed(ConsumerRecord<String, String> record) {
        project(record, Payloads.WaveClosed.class);
    }

    @KafkaListener(topics = ROUTE_ASSIGNED_TOPIC)
    public void onRouteAssigned(ConsumerRecord<String, String> record) {
        project(record, Payloads.RouteAssigned.class);
    }

    @KafkaListener(topics = ORDER_DISPATCHED_TOPIC)
    public void onOrderDispatched(ConsumerRecord<String, String> record) {
        project(record, Payloads.OrderDispatched.class);
    }

    @KafkaListener(topics = PLAN_COMPLETED_TOPIC)
    public void onPlanCompleted(ConsumerRecord<String, String> record) {
        project(record, Payloads.PlanCompleted.class);
    }

    @KafkaListener(topics = PLAN_FAILED_TOPIC)
    public void onPlanFailed(ConsumerRecord<String, String> record) {
        project(record, Payloads.PlanFailed.class);
    }

    @KafkaListener(topics = DELIVERY_STATUS_TOPIC)
    public void onDeliveryStatus(ConsumerRecord<String, String> record) {
        project(record, Payloads.DeliveryStatus.class);
    }

    @KafkaListener(topics = DELIVERY_AT_RISK_TOPIC)
    public void onDeliveryAtRisk(ConsumerRecord<String, String> record) {
        project(record, Payloads.DeliveryAtRisk.class);
    }

    @KafkaListener(topics = ROUTE_DEPARTED_TOPIC)
    public void onRouteDeparted(ConsumerRecord<String, String> record) {
        project(record, Payloads.RouteDeparted.class);
    }

    private <P extends Payloads.ToFact> void project(ConsumerRecord<String, String> record, Class<P> type) {
        EventEnvelope<P> envelope = EventRecords.parse(json, record, type);
        AtomicReference<Projection> result = new AtomicReference<>(Projection.CLEAN);
        ConsumeOutcome outcome = consumer.consumeOnce(envelope, CONSUMER,
                () -> result.set(projector.project(envelope.payload().toFact(envelope))));
        // 여기는 커밋 뒤다 — 롤백되면 consumeOnce 가 예외로 끝나 이 줄에 오지 않는다.
        if (outcome == ConsumeOutcome.PROCESSED && result.get().stale() > 0) {
            Counter.builder(MessagingMetrics.EVENT_STALE)
                    .description("순서 역전을 흡수하느라 적지 않은 판정 (ADR-017 · ADR-051)")
                    .tag(MessagingMetrics.TAG_CONSUMER, CONSUMER)
                    .tag(MessagingMetrics.TAG_EVENT_TYPE, envelope.eventType())
                    .register(meters)
                    .increment(result.get().stale());
        }
    }
}
