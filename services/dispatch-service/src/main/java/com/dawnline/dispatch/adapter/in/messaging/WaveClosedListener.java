package com.dawnline.dispatch.adapter.in.messaging;

import com.dawnline.common.GeoPoint;
import com.dawnline.dispatch.application.port.in.RunPlanCommand;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.jspecify.annotations.Nullable;
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
 *
 * <h2>랙을 싣고 간다 (§6.7, ADR-034)</h2>
 * 열화 판단의 첫 조건은 "얼마나 밀렸는가" 이고 <strong>그것을 아는 것은 여기뿐</strong>이다.
 * 유스케이스가 Kafka 를 알게 하는 대신, 어댑터가 아는 사실을 명령에 실어 보낸다.
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
     * @param kafka  이 리스너의 소비자. 랙을 묻는 데만 쓴다 (§6.7)
     */
    @KafkaListener(topics = WAVE_CLOSED_TOPIC, groupId = CONSUMER)
    public void onWaveClosed(ConsumerRecord<String, String> record,
            Consumer<?, ?> kafka) {
        EventEnvelope<JsonNode> envelope = json.readEnvelope(record.value());
        JsonNode payload = envelope.payload();
        UUID waveId = UUID.fromString(payload.get("waveId").asString());
        UUID campId = UUID.fromString(payload.get("campId").asString());
        // depot 은 required 다 (계약 README 4.4 예외 표, 2026-09-05). 없으면 계획이 성립하지
        // 않으므로 "없을 때" 를 처리하는 죽은 분기를 두지 않는다 — 없으면 여기서 터진다.
        JsonNode depot = payload.get("depot");
        GeoPoint point = GeoPoint.of(depot.get("lat").doubleValue(), depot.get("lng").doubleValue());

        Long backlog = backlogOf(kafka, record);

        consumer.runOnce(envelope, CONSUMER, () -> {
            RunPlanUseCase.Outcome outcome =
                    runPlan.run(RunPlanCommand.of(waveId, campId, point, backlog));
            log.info("웨이브 계획: waveId={} 결과={}", waveId, outcome);
        });
    }

    /**
     * 이 레코드가 온 <strong>파티션</strong>의 랙 (§6.7 첫 조건).
     *
     * <p>{@code currentLag} 는 마지막 fetch 에서 <em>캐시된</em> 값을 돌려준다(KIP-695) — 브로커에
     * 묻지 않으므로 레코드마다 불러도 비용이 없다. 대신 fetch 사이에는 갱신되지 않는다: 한 배치를
     * 오래 처리하는 동안 이 수는 그 배치를 받았을 때의 값이고, 그것은 열화 판단에 오히려 맞다 —
     * "받았을 때 얼마나 쌓여 있었는가" 가 묻는 것이다.
     *
     * <p><strong>파티션은 캠프의 상위집합이다.</strong> {@code wave.closed} 는 campId 로 키가
     * 정해지므로 같은 캠프는 언제나 같은 파티션이지만, 파티션 수 &lt; 캠프 수라 역은 아니다.
     * 그래서 이 값은 "이 캠프의 랙" 이 아니라 <em>"이 캠프가 실린 소비 흐름의 랙"</em>이다.
     * 열화가 그 흐름 단위로 일어나는 것은 맞다 — 같은 파티션의 웨이브들은 실제로 한 줄로 서서
     * 기다린다. 전역 랙보다 좁고 캠프 단위보다는 넓다.
     *
     * <p>비어 있으면 {@code null} 이다 — <strong>0 이 아니라 모름</strong>이다(리밸런스 직후,
     * 할당되지 않은 파티션). 접으면 랙 조건이 조용히 「아니오」가 된다.
     */
    private static @Nullable Long backlogOf(Consumer<?, ?> kafka,
            ConsumerRecord<?, ?> record) {

        OptionalLong lag = kafka.currentLag(new TopicPartition(record.topic(), record.partition()));
        return lag.isPresent() ? lag.getAsLong() : null;
    }
}
