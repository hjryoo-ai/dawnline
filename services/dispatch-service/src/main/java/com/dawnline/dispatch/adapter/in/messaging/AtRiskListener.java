package com.dawnline.dispatch.adapter.in.messaging;

import com.dawnline.dispatch.application.DispatchMetrics;
import com.dawnline.dispatch.application.port.in.ReplanRouteUseCase;
import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.JsonNode;

/**
 * {@code delivery.at-risk} 수신 → §6.8 부분 재계획 (ADR-046 · ADR-048).
 *
 * <h2>멱등 게이트는 필요하지만 충분하지 않다</h2>
 * {@link IdempotentConsumer} 는 <em>같은 이벤트의 재배달</em>을 막는다(불변규칙 2). 재계획
 * 중복을 막는 것은 그것이 아니라 {@code routes.last_replanned_at} 이다 — 편차가 계속 커지면
 * tracking 이 <strong>새 이벤트</strong>를 내고(ADR-046 결정 1), 두 at-risk 는 {@code eventId}
 * 가 달라 {@code processed_events} 에게는 둘 다 처음 보는 이벤트다. 둘 다 있어야 한다.
 *
 * <h2>결과는 세지만 던지지 않는다</h2>
 * 「후보가 없다」·「이득이 없다」는 재시도로 달라지지 않는 <em>결과</em>다. DLQ 로 보내면 고칠 수
 * 없는 것이 재시도되고 사람이 열어도 할 일이 없다 — 그래서 {@code dawnline_replan_total} 의
 * {@code outcome} 라벨이 그 자리를 대신한다(ADR-048 결정 5).
 *
 * <p>카운터는 유스케이스 <strong>밖</strong>에서 올린다. 안에서 올리면 롤백된 재계획의 숫자가
 * 남고, 그 차이는 장애 때 가장 커진다 — 지표가 가장 많이 읽히는 순간에 가장 많이 틀린다.
 */
public class AtRiskListener {

    private static final Logger log = LoggerFactory.getLogger(AtRiskListener.class);

    /** {@code Topics.forEvent("delivery.at-risk", 1)} 와 같아야 한다. 테스트가 확인한다. */
    static final String AT_RISK_TOPIC = "dawnline.delivery.at-risk.v1";

    /** {@code processed_events.consumer} 값 (§8.5). 다른 리스너들과 같은 값이다. */
    static final String CONSUMER = "dispatch-service";

    private final IdempotentConsumer consumer;
    private final ReplanRouteUseCase replan;
    private final EventJson json;
    private final DispatchMetrics metrics;

    /**
     * @param consumer 멱등 게이트 (불변규칙 2)
     * @param replan   재계획 유스케이스
     * @param json     봉투 역직렬화
     * @param metrics  §9.1 메트릭
     */
    public AtRiskListener(IdempotentConsumer consumer, ReplanRouteUseCase replan, EventJson json,
            DispatchMetrics metrics) {

        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.replan = Objects.requireNonNull(replan, "replan");
        this.json = Objects.requireNonNull(json, "json");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * @param record 브로커 레코드
     */
    @KafkaListener(topics = AT_RISK_TOPIC, groupId = CONSUMER)
    public void onAtRisk(ConsumerRecord<String, String> record) {
        EventEnvelope<JsonNode> envelope = json.readEnvelope(record.value());
        JsonNode payload = envelope.payload();

        consumer.runOnce(envelope, CONSUMER, () -> {
            ReplanRouteUseCase.Outcome outcome = replan.replan(AtRiskPayload.toCommand(payload));
            metrics.replanned(outcome);
            log.debug("재계획을 마쳤다. eventId={}, outcome={}", envelope.eventId(), outcome.label());
        });
    }
}
