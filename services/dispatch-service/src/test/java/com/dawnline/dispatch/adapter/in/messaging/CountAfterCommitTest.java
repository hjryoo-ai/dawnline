package com.dawnline.dispatch.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.dispatch.application.DispatchMetrics;
import com.dawnline.dispatch.application.port.in.ReplanRouteUseCase;
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
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.node.ObjectNode;

/**
 * 소비 경로의 카운터는 <strong>커밋 뒤에</strong> 센다 (CLAUDE.md 「카운터는 커밋 뒤에 센다」).
 *
 * <p>{@link IdempotentConsumer#runOnce} 의 콜백 안은 {@code processed_events} 를 쓴 트랜잭션이 아직 커밋되기 전이다 — 유스케이스의
 * {@code @Transactional} 은 그 트랜잭션에 합류한다. 두 리스너가 콜백 안에서 세고 있었고, {@code ReplanIT} 가 CI 에서 카운터를
 * 기다린 뒤 {@code processed_events} 를 1 행으로 읽어 드러났다(2026-09-27). 순서는 읽어서 보이지 않으므로 여기서 커밋을 실패시키고
 * 카운터가 0 인지 본다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CountAfterCommitTest {

    private static final EventContracts CONTRACTS = EventContracts.load();
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private IdempotentConsumer consumer(PlatformTransactionManager transactions) {
        return new IdempotentConsumer(new InMemoryProcessedEvents(), transactions, meters, CLOCK);
    }

    // --- at-risk → 재계획 ----------------------------------------------------------------------------------

    private AtRiskListener atRisk(PlatformTransactionManager transactions) {
        // 유스케이스는 게이트로 쓰기를 감싼다(ADR-068 결정 1) — 게이트가 커밋에서 실패하면 예외가 여기까지 온다.
        return new AtRiskListener(consumer(transactions),
                (command, gate) -> gate.enter(() -> { })
                        ? Optional.of(ReplanRouteUseCase.Outcome.APPLIED) : Optional.empty(),
                EventJson.standard(), new DispatchMetrics(meters));
    }

    private ConsumerRecord<String, String> atRiskRecord() {
        String value = CONTRACTS.readTree(CONTRACTS.contractsDirectory()
                .resolve("examples/delivery.at-risk.v1.example.json")).toString();
        return new ConsumerRecord<>(AtRiskListener.AT_RISK_TOPIC, 0, 0L, "key", value);
    }

    private double replanned() {
        Counter counter = meters.find(DawnlineMetrics.REPLAN.meterName())
                .tag("outcome", ReplanRouteUseCase.Outcome.APPLIED.label()).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    void 재계획은_커밋_뒤에_센다() {
        atRisk(CommitOutcomeTransactions.committing()).onAtRisk(atRiskRecord());

        assertThat(replanned()).isEqualTo(1.0);
    }

    @Test
    void 재계획의_커밋이_실패하면_세지_않는다() {
        assertThatThrownBy(() -> atRisk(CommitOutcomeTransactions.failingOnCommit()).onAtRisk(atRiskRecord()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(replanned()).as("롤백된 재계획의 숫자가 남지 않는다").isZero();
    }

    // --- delivery.status 의 모르는 값 ------------------------------------------------------------------------

    private DeliveryStatusListener deliveryStatus(PlatformTransactionManager transactions) {
        return new DeliveryStatusListener(consumer(transactions), command -> { },
                EventJson.standard(), new DispatchMetrics(meters));
    }

    private ConsumerRecord<String, String> unknownStatusRecord() {
        ObjectNode envelope = (ObjectNode) CONTRACTS.readTree(CONTRACTS.contractsDirectory()
                .resolve("examples/delivery.status.v1.example.json"));
        ((ObjectNode) envelope.get("payload")).put("status", "TELEPORTED");
        return new ConsumerRecord<>(DeliveryStatusListener.DELIVERY_STATUS_TOPIC, 0, 0L, "key", envelope.toString());
    }

    private double unknownStatus() {
        Counter counter = meters.find(DawnlineMetrics.EVENT_REJECTED.meterName())
                .tag(MessagingMetrics.TAG_REASON, DispatchMetrics.DELIVERY_STATUS_UNKNOWN_REASON).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    void 모르는_배송_상태값은_커밋_뒤에_센다() {
        deliveryStatus(CommitOutcomeTransactions.committing()).onDeliveryStatus(unknownStatusRecord());

        assertThat(unknownStatus()).isEqualTo(1.0);
    }

    @Test
    void 모르는_배송_상태값의_커밋이_실패하면_세지_않는다() {
        assertThatThrownBy(() -> deliveryStatus(CommitOutcomeTransactions.failingOnCommit())
                .onDeliveryStatus(unknownStatusRecord())).isInstanceOf(IllegalStateException.class);

        assertThat(unknownStatus()).isZero();
    }
}
