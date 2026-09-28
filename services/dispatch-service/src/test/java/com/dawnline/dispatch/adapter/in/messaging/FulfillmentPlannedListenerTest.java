package com.dawnline.dispatch.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.dispatch.application.port.in.LoadCandidateUseCase;
import com.dawnline.messaging.MessagingMetrics;
import com.dawnline.messaging.contract.EventContracts;
import com.dawnline.messaging.idempotency.CommitOutcomeTransactions;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.idempotency.InMemoryProcessedEvents;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.observability.DawnlineMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** {@code fulfillment.planned} 리스너가 유스케이스의 결과를 무엇으로 번역하는가 (ADR-074 결정 2). */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FulfillmentPlannedListenerTest {

    private static final EventContracts CONTRACTS = EventContracts.load();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void 취소가_먼저_온_주문의_적재는_사유와_함께_거부한다() {
        // fulfillment 의 cancelled_before_placed 와 짝(ADR-022). 거부는 DLQ 가 아니고 멱등 기록은 남는다(§4.6).
        FulfillmentPlannedListener listener = new FulfillmentPlannedListener(
                new IdempotentConsumer(new InMemoryProcessedEvents(), CommitOutcomeTransactions.committing(), meters,
                        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)),
                snapshot -> LoadCandidateUseCase.Outcome.CANCELLED_FIRST, EventJson.standard());
        String value = CONTRACTS.readTree(CONTRACTS.contractsDirectory()
                .resolve("examples/fulfillment.planned.v1.example.json")).toString();

        listener.onFulfillmentPlanned(
                new ConsumerRecord<>(FulfillmentPlannedListener.FULFILLMENT_PLANNED_TOPIC, 0, 0L, "key", value));

        Counter rejected = meters.find(DawnlineMetrics.EVENT_REJECTED.meterName())
                .tag(MessagingMetrics.TAG_CONSUMER, FulfillmentPlannedListener.CONSUMER)
                .tag(MessagingMetrics.TAG_EVENT_TYPE, "fulfillment.planned")
                .tag(MessagingMetrics.TAG_REASON, FulfillmentPlannedListener.CANCELLED_BEFORE_CANDIDATE).counter();
        assertThat(rejected).as("사유 라벨로 센다").isNotNull();
        assertThat(rejected.count()).isEqualTo(1.0);
    }

    @Test
    void 거부_사유는_짝의_이름과_같은_꼴이다() {
        // 문자열이 대시보드 · 알림의 라벨 값이다 — 바뀌면 조용히 다른 시계열이 된다(fulfillment 의 OrderEventListenerTopicsTest 와 같은 고정).
        assertThat(FulfillmentPlannedListener.CANCELLED_BEFORE_CANDIDATE).isEqualTo("cancelled_before_candidate");
    }

    @Test
    void 적재했으면_거부하지_않는다() {
        FulfillmentPlannedListener listener = new FulfillmentPlannedListener(
                new IdempotentConsumer(new InMemoryProcessedEvents(), CommitOutcomeTransactions.committing(), meters,
                        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)),
                snapshot -> LoadCandidateUseCase.Outcome.LOADED, EventJson.standard());
        String value = CONTRACTS.readTree(CONTRACTS.contractsDirectory()
                .resolve("examples/fulfillment.planned.v1.example.json")).toString();

        listener.onFulfillmentPlanned(
                new ConsumerRecord<>(FulfillmentPlannedListener.FULFILLMENT_PLANNED_TOPIC, 0, 0L, "key", value));

        assertThat(meters.find(DawnlineMetrics.EVENT_REJECTED.meterName()).counters()).isEmpty();
    }
}
