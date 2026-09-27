package com.dawnline.dispatch.adapter.in.messaging;

import com.dawnline.dispatch.application.DispatchMetrics;
import com.dawnline.dispatch.application.port.in.ReplanRouteUseCase;
import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import java.util.Objects;
import java.util.Optional;
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
 * <p>카운터는 <strong>커밋 뒤에</strong> 올린다 — 유스케이스가 돌아온 뒤, 곧 게이트({@link IdempotentConsumer#runOnce})가 돌아온 뒤다. 안에서 올리면 롤백된
 * 재계획의 숫자가 남고, 그 차이는 장애 때 가장 커진다 — 지표가 가장 많이 읽히는 순간에 가장 많이 틀린다.
 * <strong>2026-09-27 정정</strong>: 이 문단은 전에 「유스케이스 밖」이라고 적었고 카운터는 {@code runOnce} 의 콜백 안에 있었다 —
 * 유스케이스의 {@code @Transactional} 은 바깥({@code processed_events})의 트랜잭션에 합류하므로 콜백 안은 아직 커밋 전이다.
 * {@code ReplanIT} 가 CI 에서 카운터를 기다린 뒤 {@code processed_events} 를 1 행으로 읽어 드러났다(근거: 관측 — 재현은 CI 1회).
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

        // 멱등 게이트는 재계획의 <em>쓰기</em>만 감싼다 — 계산은 그 앞에서 트랜잭션 없이 돈다(ADR-068 결정 1). 게이트를 여기서
        // 통째로 두르면 계산 동안 커넥션 하나가 idle in transaction 이고, 그 창에 끝난 배송을 쓰기가 덮는다.
        Optional<ReplanRouteUseCase.Outcome> outcome = replan.replan(AtRiskPayload.toCommand(payload),
                write -> consumer.runOnce(envelope, CONSUMER, write));
        // 여기는 커밋 뒤다 — 롤백되면 runOnce 가 예외로 끝나 이 줄에 오지 않는다. 비어 있으면 같은 이벤트를 이미 처리했다.
        outcome.ifPresent(done -> {
            metrics.replanned(done);
            log.debug("재계획을 마쳤다. eventId={}, outcome={}", envelope.eventId(), done.label());
        });
    }
}
