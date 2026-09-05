package com.dawnline.dispatch.adapter.in.messaging;

import com.dawnline.common.GeoPoint;
import com.dawnline.dispatch.application.port.in.RunPlanCommand;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import java.util.Objects;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.JsonNode;

/**
 * {@code wave.closed} 수신 → 계획 실행 (§4.1, §5.3).
 *
 * <h2>멱등이 두 겹이다</h2>
 * {@link IdempotentConsumer} 가 같은 {@code eventId} 의 재전달을 막고, {@code route_plans.wave_id}
 * UNIQUE 가 <em>다른</em> eventId 로 온 같은 웨이브를 막는다(§5.3). 앞의 것은 14일 뒤 정리되고
 * (§4.4) 뒤의 것은 남으므로, 둘이 막는 기간이 다르다.
 */
public class WaveClosedListener {

    /** {@code Topics.forEvent("wave.closed", 1)} 와 같아야 한다. 테스트가 확인한다. */
    static final String WAVE_CLOSED_TOPIC = "dawnline.wave.closed.v1";

    /** {@code processed_events.consumer} 값 (§8.5). */
    static final String CONSUMER = "dispatch-service";

    private static final Logger log = LoggerFactory.getLogger(WaveClosedListener.class);

    private final IdempotentConsumer consumer;
    private final RunPlanUseCase runPlan;
    private final EventJson json;

    /**
     * @param consumer 멱등 게이트 (불변규칙 2)
     * @param runPlan  계획 유스케이스
     * @param json     봉투 역직렬화
     */
    public WaveClosedListener(IdempotentConsumer consumer, RunPlanUseCase runPlan, EventJson json) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.runPlan = Objects.requireNonNull(runPlan, "runPlan");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * @param record 브로커 레코드
     */
    @KafkaListener(topics = WAVE_CLOSED_TOPIC, groupId = CONSUMER)
    public void onWaveClosed(ConsumerRecord<String, String> record) {
        EventEnvelope<JsonNode> envelope = json.readEnvelope(record.value());
        JsonNode payload = envelope.payload();
        UUID waveId = UUID.fromString(payload.get("waveId").asString());
        UUID campId = UUID.fromString(payload.get("campId").asString());
        // depot 은 required 다 (계약 README 4.4 예외 표, 2026-09-05). 없으면 계획이 성립하지
        // 않으므로 "없을 때" 를 처리하는 죽은 분기를 두지 않는다 — 없으면 여기서 터진다.
        JsonNode depot = payload.get("depot");
        GeoPoint point = GeoPoint.of(depot.get("lat").doubleValue(), depot.get("lng").doubleValue());

        consumer.runOnce(envelope, CONSUMER, () -> {
            RunPlanUseCase.Outcome outcome = runPlan.run(RunPlanCommand.of(waveId, campId, point));
            log.info("웨이브 계획: waveId={} 결과={}", waveId, outcome);
        });
    }
}
