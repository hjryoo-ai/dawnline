package com.dawnline.fulfillment.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.fulfillment.application.port.in.CancelFulfillmentOrderUseCase;
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
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 취소 선착(순서 뒤바뀜 흡수)의 카운터는 <strong>커밋 뒤에</strong> 센다 (CLAUDE.md 「카운터는 커밋 뒤에 센다」).
 *
 * <p>{@code consumeOnce} 의 콜백 안은 {@code processed_events} 를 쓴 트랜잭션이 커밋되기 전이다 — 전에는 거기서 셌다
 * (2026-09-27, dispatch 의 {@code ReplanIT} 가 같은 모양을 CI 에서 드러낸 뒤 전 서비스를 훑어 찾았다).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CountAfterCommitTest {

    private static final EventContracts CONTRACTS = EventContracts.load();
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private OrderEventListener listener(PlatformTransactionManager transactions) {
        return new OrderEventListener(
                new IdempotentConsumer(new InMemoryProcessedEvents(), transactions, meters, CLOCK),
                (snapshot, eventId) -> {
                    throw new AssertionError("order.placed 는 이 테스트의 대상이 아니다");
                },
                (orderId, cancelledAt) -> CancelFulfillmentOrderUseCase.CancelOutcome.CANCELLED_BEFORE_PLACED,
                EventJson.standard(), meters);
    }

    private ConsumerRecord<String, String> cancelled() {
        String value = CONTRACTS.readTree(CONTRACTS.contractsDirectory()
                .resolve("examples/order.cancelled.v1.example.json")).toString();
        return new ConsumerRecord<>(OrderEventListener.ORDER_CANCELLED_TOPIC, 0, 0L, "key", value);
    }

    private double absorbed() {
        Counter counter = meters.find(DawnlineMetrics.EVENT_STALE.meterName())
                .tag(MessagingMetrics.TAG_CONSUMER, OrderEventListener.CONSUMER)
                .tag(MessagingMetrics.TAG_EVENT_TYPE, "order.cancelled").counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    void 취소_선착은_커밋_뒤에_센다() {
        listener(CommitOutcomeTransactions.committing()).onOrderCancelled(cancelled());

        assertThat(absorbed()).isEqualTo(1.0);
    }

    @Test
    void 커밋이_실패하면_세지_않는다() {
        assertThatThrownBy(() -> listener(CommitOutcomeTransactions.failingOnCommit()).onOrderCancelled(cancelled()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(absorbed()).isZero();
    }
}
