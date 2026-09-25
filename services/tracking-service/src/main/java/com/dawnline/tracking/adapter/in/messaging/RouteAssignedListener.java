package com.dawnline.tracking.adapter.in.messaging;

import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.MessagingMetrics;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.messaging.kafka.EventRecords;
import com.dawnline.observability.DawnlineMeters;
import com.dawnline.observability.DawnlineMetrics;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.Outcome;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * {@code route.assigned} 수신 (DESIGN.md §4.1, §5.4).
 *
 * <p>어댑터가 하는 일은 셋뿐이다 — 봉투를 열고, {@link IdempotentConsumer} 로 한 번만 실행하고
 * (불변규칙 2), 결과를 메트릭·로그로 번역한다. 개정 비교도 상태 전이도 여기 없다.
 *
 * <h2>멱등이 두 겹이다</h2>
 * {@code processed_events} 는 <strong>같은 이벤트</strong>의 재전달을 막고,
 * {@code route_revisions} 는 <strong>지난 개정</strong>을 막는다(§8.5, ADR-045). 둘은 다른
 * 질문이다 — 재계획이 개정 3을 새 {@code eventId} 로 다시 발행하면 첫 겹은 통과한다.
 *
 * <h2>지난 개정은 거부가 아니다</h2>
 * {@code dawnline_event_rejected_total} 을 올리지 않는다. 그 카운터는 「처리하지 못했다」를 세는
 * 값이고, 지난 개정을 버리는 것은 <em>설계된 동작</em>이다(순서 역전 흡수). fulfillment 가
 * {@code order.cancelled} 선착을 셀 때와 같은 자리에 {@code dawnline_event_stale_total} 로 센다 —
 * 늘어나면 어딘가 지연이 커졌다는 뜻이다.
 *
 * <h2>토픽 이름을 리터럴로 적는 이유</h2>
 * {@code @KafkaListener} 의 {@code topics} 는 컴파일 타임 상수여야 해서 {@code Topics.forEvent}
 * 를 부를 수 없다. 두 값이 어긋나지 않도록 {@code RouteAssignedListenerTopicsTest} 가 대조한다 —
 * 오타는 컨슈머가 <strong>조용히 아무것도 받지 않는</strong> 형태로 나타난다.
 */
public class RouteAssignedListener {

    /** {@code Topics.forEvent("route.assigned", 1)} 와 같아야 한다. 테스트가 확인한다. */
    static final String ROUTE_ASSIGNED_TOPIC = "dawnline.route.assigned.v1";

    /** {@code processed_events.consumer} 값 (§8.5). 인스턴스마다 달라지면 멱등이 깨진다. */
    static final String CONSUMER = "tracking-service";

    /** {@code dawnline_event_stale_total} 의 {@code eventType} 태그. */
    static final String EVENT_TYPE = "route.assigned";

    private static final Logger log = LoggerFactory.getLogger(RouteAssignedListener.class);

    private final IdempotentConsumer consumer;
    private final ApplyRouteAssignmentUseCase applyRouteAssignment;
    private final EventJson json;
    private final MeterRegistry meters;

    /**
     * @param consumer             멱등 게이트 (불변규칙 2)
     * @param applyRouteAssignment 개정 반영 유스케이스
     * @param json                 이벤트 JSON 코덱
     * @param meters               Micrometer 레지스트리 (§9.1)
     */
    public RouteAssignedListener(IdempotentConsumer consumer,
            ApplyRouteAssignmentUseCase applyRouteAssignment, EventJson json, MeterRegistry meters) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.applyRouteAssignment = Objects.requireNonNull(applyRouteAssignment, "applyRouteAssignment");
        this.json = Objects.requireNonNull(json, "json");
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    /**
     * {@code route.assigned} — 라우트 확정·개정 (§4.1).
     *
     * @param record Kafka 레코드
     */
    @KafkaListener(topics = ROUTE_ASSIGNED_TOPIC)
    public void onRouteAssigned(ConsumerRecord<String, String> record) {
        EventEnvelope<RouteAssignedPayload> envelope =
                EventRecords.parse(json, record, RouteAssignedPayload.class);
        RouteAssignedPayload payload = envelope.payload();

        consumer.consumeOnce(envelope, CONSUMER, () -> {
            Outcome outcome = applyRouteAssignment.apply(payload.toAssignment());
            if (outcome.kind() == Outcome.Kind.STALE) {
                countStale();
                log.debug("지난 개정이라 무시했다. routeId={}, revision={}",
                        payload.routeId(), payload.revision());
                return;
            }
            // 주소도 주문 id 목록도 남기지 않는다 (§9.3 — 전체 주소·고객 식별 정보 금지).
            log.debug("라우트 개정을 반영했다. routeId={}, revision={}, created={}, revised={}, "
                            + "cancelled={}, keptTerminal={}",
                    payload.routeId(), payload.revision(), outcome.created(), outcome.revised(),
                    outcome.cancelled(), outcome.keptTerminal());
        });
    }

    private void countStale() {
        DawnlineMeters.counter(meters, DawnlineMetrics.EVENT_STALE,
                MessagingMetrics.TAG_CONSUMER, CONSUMER,
                MessagingMetrics.TAG_EVENT_TYPE, EVENT_TYPE)
                .increment();
    }
}
