package com.dawnline.fulfillment.adapter.in.messaging;

import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.MessagingMetrics;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.messaging.kafka.EventRecords;
import com.dawnline.fulfillment.application.port.in.RecordPlanResultUseCase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * 계획 결과 수신 (§5.2 웨이브 수명주기, ADR-024).
 *
 * <p>이 두 리스너가 없으면 웨이브가 {@code CLOSED} 에서 멈추고, 그러면
 * [ADR-023](docs/adr/ADR-023-fulfillment-retention.md) 의 정리 배치가 {@code PLANNED} 주문 행을
 * <strong>영원히 지우지 못한다</strong> — 보존 정책이 조용히 무한 보존이 된다.
 *
 * <p>발행자는 Phase 3 의 dispatch-service 다. 계약은 소비자인 이쪽이 Phase 2 에 정의했으므로
 * (ADR-024 결정 5) 리스너와 통합 테스트는 예시 이벤트로 지금 완결된다.
 */
public class PlanResultListener {

    /** {@code Topics.forEvent("plan.completed", 1)} 와 같아야 한다. 테스트가 확인한다. */
    static final String PLAN_COMPLETED_TOPIC = "dawnline.plan.completed.v1";

    /** {@code Topics.forEvent("plan.failed", 1)} 와 같아야 한다. */
    static final String PLAN_FAILED_TOPIC = "dawnline.plan.failed.v1";

    /** 이미 계획된 웨이브에 늦게 도착한 {@code plan.failed} 의 거부 사유 (ADR-024 결정 4). */
    static final String WAVE_ALREADY_PLANNED = "wave_already_planned";

    private static final Logger log = LoggerFactory.getLogger(PlanResultListener.class);

    private final IdempotentConsumer consumer;
    private final RecordPlanResultUseCase recordResult;
    private final EventJson json;
    private final MeterRegistry meters;

    /**
     * @param consumer     멱등 게이트 (불변규칙 2)
     * @param recordResult 계획 결과 기록 유스케이스
     * @param json         이벤트 JSON 코덱
     * @param meters       Micrometer 레지스트리 (§9.1)
     */
    public PlanResultListener(IdempotentConsumer consumer, RecordPlanResultUseCase recordResult,
            EventJson json, MeterRegistry meters) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.recordResult = Objects.requireNonNull(recordResult, "recordResult");
        this.json = Objects.requireNonNull(json, "json");
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    /**
     * {@code plan.completed} — 웨이브 계획 완료 (ADR-024).
     *
     * @param record Kafka 레코드
     */
    @KafkaListener(topics = PLAN_COMPLETED_TOPIC)
    public void onPlanCompleted(ConsumerRecord<String, String> record) {
        EventEnvelope<PlanCompletedPayload> envelope =
                EventRecords.parse(json, record, PlanCompletedPayload.class);
        UUID waveId = envelope.payload().waveId();

        consumer.consumeOnce(envelope, OrderEventListener.CONSUMER, () -> {
            RecordPlanResultUseCase.PlanResultOutcome outcome = recordResult.completed(waveId);
            countStale(envelope.eventType(), outcome);
            log.debug("계획 완료 수신. waveId={}, outcome={}", waveId, outcome);
        });
    }

    /**
     * {@code plan.failed} — 계획 실행 실패 (§5.3 Plan {@code FAILED}).
     *
     * <p>이미 {@code PLANNED} 인 웨이브에 도착하면 무시하고 센다. 재실행이 만드는 순서 뒤바뀜이고
     * (두 이벤트는 다른 토픽이다), 그대로 두면 라우트가 이미 나간 웨이브가 실패로 표시된다.
     *
     * @param record Kafka 레코드
     */
    @KafkaListener(topics = PLAN_FAILED_TOPIC)
    public void onPlanFailed(ConsumerRecord<String, String> record) {
        EventEnvelope<PlanFailedPayload> envelope =
                EventRecords.parse(json, record, PlanFailedPayload.class);
        PlanFailedPayload payload = envelope.payload();

        consumer.consumeOnce(envelope, OrderEventListener.CONSUMER, () -> {
            RecordPlanResultUseCase.PlanResultOutcome outcome = recordResult.failed(payload.waveId());
            if (outcome == RecordPlanResultUseCase.PlanResultOutcome.STALE) {
                countRejected();
            }
            countStale(envelope.eventType(), outcome);
            log.debug("계획 실패 수신. waveId={}, reason={}, outcome={}",
                    payload.waveId(), payload.reason(), outcome);
        });
    }

    private void countStale(String eventType, RecordPlanResultUseCase.PlanResultOutcome outcome) {
        if (outcome != RecordPlanResultUseCase.PlanResultOutcome.STALE) {
            return;
        }
        Counter.builder(MessagingMetrics.EVENT_STALE)
                .description("이미 지나온 지점으로의 전이라 무시한 이벤트 (ADR-017·024)")
                .tag(MessagingMetrics.TAG_CONSUMER, OrderEventListener.CONSUMER)
                .tag(MessagingMetrics.TAG_EVENT_TYPE, eventType)
                .register(meters)
                .increment();
    }

    private void countRejected() {
        Counter.builder(MessagingMetrics.EVENT_REJECTED)
                .description("계획된 웨이브에 늦게 도착한 plan.failed (ADR-024 결정 4)")
                .tag(MessagingMetrics.TAG_REASON, WAVE_ALREADY_PLANNED)
                .register(meters)
                .increment();
    }
}
