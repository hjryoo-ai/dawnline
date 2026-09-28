package com.dawnline.dispatch.adapter.in.messaging;

import com.dawnline.dispatch.application.DispatchMetrics;
import com.dawnline.dispatch.application.port.in.RecordRouteDepartureUseCase;
import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.JsonNode;

/**
 * {@code dawnline.delivery.route-departed.v1} 소비자 (ADR-072) — 재계획의 앵커가 될 출발 사실.
 *
 * <p>라우트가 dispatch 에 없으면(보존이 지운 뒤의 재생) 무시하고 {@code dawnline_event_stale_total} 로 센다 — 커밋 뒤에.
 */
public class RouteDepartedListener {

    /** {@code Topics.forEvent("delivery.route-departed", 1)} 와 같아야 한다. 테스트가 확인한다. */
    static final String ROUTE_DEPARTED_TOPIC = "dawnline.delivery.route-departed.v1";

    /** {@code processed_events.consumer} 값 (§8.5). 다른 리스너들과 같은 값이다. */
    static final String CONSUMER = "dispatch-service";

    private final IdempotentConsumer consumer;
    private final RecordRouteDepartureUseCase recordDeparture;
    private final EventJson json;
    private final DispatchMetrics metrics;

    /**
     * @param consumer        멱등 게이트 (불변규칙 2)
     * @param recordDeparture 출발 유스케이스
     * @param json            봉투 역직렬화
     * @param metrics         §9.1 메트릭
     */
    public RouteDepartedListener(IdempotentConsumer consumer, RecordRouteDepartureUseCase recordDeparture,
            EventJson json, DispatchMetrics metrics) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.recordDeparture = Objects.requireNonNull(recordDeparture, "recordDeparture");
        this.json = Objects.requireNonNull(json, "json");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * @param record 브로커 레코드
     */
    @KafkaListener(topics = ROUTE_DEPARTED_TOPIC, groupId = CONSUMER)
    public void onRouteDeparted(ConsumerRecord<String, String> record) {
        EventEnvelope<JsonNode> envelope = json.readEnvelope(record.value());
        AtomicBoolean gone = new AtomicBoolean();
        boolean ran = consumer.runOnce(envelope, CONSUMER,
                () -> gone.set(!recordDeparture.record(RouteDepartedPayload.toCommand(envelope.payload()))));
        // 커밋 뒤에 센다(CLAUDE.md) — runOnce 가 돌아왔으면 processed_events 가 커밋됐다.
        if (ran && gone.get()) {
            metrics.routeDepartedStale();
        }
    }
}
