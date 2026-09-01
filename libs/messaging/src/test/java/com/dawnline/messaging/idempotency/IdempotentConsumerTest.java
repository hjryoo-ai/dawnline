package com.dawnline.messaging.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.Ids;
import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.MessagingMetrics;
import com.dawnline.messaging.support.InMemoryProcessedEventRepository;
import com.dawnline.messaging.support.MutableClock;
import com.dawnline.messaging.support.TestTransactionManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link IdempotentConsumer} — 모든 리스너가 통과하는 멱등 게이트
 * (CLAUDE.md 불변규칙 2, DESIGN.md §4.4·§4.6·§8.5).
 */
class IdempotentConsumerTest {

    private static final Instant NOW = Instant.parse("2026-08-29T13:20:11.482Z");
    private static final String CONSUMER = "fulfillment-service";

    private final MutableClock clock = MutableClock.at(NOW);
    private final Ids ids = new Ids(clock, new Random(42));
    private final InMemoryProcessedEventRepository repository = new InMemoryProcessedEventRepository();
    private final TestTransactionManager transactionManager = new TestTransactionManager();
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final IdempotentConsumer consumer =
            new IdempotentConsumer(repository, transactionManager, meters, clock);

    @Test
    void consumeOnce_처음이면_비즈니스_로직을_실행한다() {
        List<String> executed = new ArrayList<>();

        ConsumeOutcome outcome = consumer.consumeOnce(envelope(), CONSUMER, () -> executed.add("실행"));

        assertThat(outcome).isEqualTo(ConsumeOutcome.PROCESSED);
        assertThat(executed).containsExactly("실행");
        assertThat(counter(MessagingMetrics.OUTCOME_OK)).isEqualTo(1.0);
    }

    @Test
    void consumeOnce_같은_이벤트를_두_번_받으면_한_번만_실행한다() {
        // Phase 0 DoD 의 핵심 규칙. 통합 테스트(OutboxRelayIT)가 실제 DB 로 다시 확인한다.
        EventEnvelope<Map<String, Object>> envelope = envelope();
        List<String> executed = new ArrayList<>();

        ConsumeOutcome first = consumer.consumeOnce(envelope, CONSUMER, () -> executed.add("실행"));
        ConsumeOutcome second = consumer.consumeOnce(envelope, CONSUMER, () -> executed.add("실행"));

        assertThat(first).isEqualTo(ConsumeOutcome.PROCESSED);
        assertThat(second).isEqualTo(ConsumeOutcome.DUPLICATE);
        assertThat(executed).hasSize(1);
        assertThat(counter(MessagingMetrics.OUTCOME_DUP)).isEqualTo(1.0);
    }

    @Test
    void consumeOnce_소비자가_다르면_각자_한_번씩_실행한다() {
        // (event_id, consumer) 복합키의 의미. dispatch 와 tracking 이 같은 이벤트를 각자 처리한다.
        EventEnvelope<Map<String, Object>> envelope = envelope();
        List<String> executed = new ArrayList<>();

        consumer.consumeOnce(envelope, "dispatch-service", () -> executed.add("dispatch"));
        consumer.consumeOnce(envelope, "tracking-service", () -> executed.add("tracking"));

        assertThat(executed).containsExactly("dispatch", "tracking");
    }

    @Test
    void consumeOnce_거부되면_DLQ가_아니라_커밋하고_넘어간다() {
        // §4.6 세 번째 줄. 거부는 재시도해도 결과가 같고 운영자가 손댈 것도 없다.
        ConsumeOutcome outcome = consumer.consumeOnce(envelope(), CONSUMER, () -> {
            throw new EventRejectedException("ORDER_ALREADY_DISPATCHED", "이미 디스패치된 주문입니다");
        });

        assertThat(outcome).isEqualTo(ConsumeOutcome.REJECTED);
        assertThat(transactionManager.commits()).isEqualTo(1);
        assertThat(transactionManager.rollbacks()).isZero();
        assertThat(counter(MessagingMetrics.OUTCOME_REJECTED)).isEqualTo(1.0);
        assertThat(meters.get(MessagingMetrics.EVENT_REJECTED)
                .tag(MessagingMetrics.TAG_REASON, "ORDER_ALREADY_DISPATCHED")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void consumeOnce_거부된_이벤트는_다시_처리되지_않는다() {
        EventEnvelope<Map<String, Object>> envelope = envelope();
        consumer.consumeOnce(envelope, CONSUMER, () -> {
            throw new EventRejectedException("ORDER_ALREADY_DISPATCHED", "이미 디스패치된 주문입니다");
        });

        ConsumeOutcome second = consumer.consumeOnce(envelope, CONSUMER, () -> { });

        assertThat(second).isEqualTo(ConsumeOutcome.DUPLICATE);
    }

    @Test
    void consumeOnce_일시적_오류는_그대로_던지고_롤백한다() {
        // §4.6 첫 줄: 재시도 대상. 예외를 그대로 올려 에러 핸들러가 재시도→DLQ 경로를 태우게 한다.
        // (실제 DB 에서 선점이 롤백되는지는 OutboxRelayIT 가 확인한다 — 인메모리 가짜는 롤백을 흉내 내지 않는다.)
        EventEnvelope<Map<String, Object>> envelope = envelope();

        assertThatThrownBy(() -> consumer.consumeOnce(envelope, CONSUMER, () -> {
            throw new IllegalStateException("DB 타임아웃");
        })).isInstanceOf(IllegalStateException.class).hasMessage("DB 타임아웃");

        assertThat(transactionManager.rollbacks()).isEqualTo(1);
        assertThat(transactionManager.commits()).isZero();
    }

    @Test
    void runOnce_실행됐을_때만_true() {
        EventEnvelope<Map<String, Object>> envelope = envelope();

        assertThat(consumer.runOnce(envelope, CONSUMER, () -> { })).isTrue();
        assertThat(consumer.runOnce(envelope, CONSUMER, () -> { })).isFalse();
    }

    @Test
    void consumeOnce_processed_events에_소비자별로_기록한다() {
        EventEnvelope<Map<String, Object>> envelope = envelope();

        consumer.consumeOnce(envelope, "dispatch-service", () -> { });

        assertThat(repository.isProcessed(envelope.eventId(), "dispatch-service")).isTrue();
        assertThat(repository.isProcessed(envelope.eventId(), "tracking-service")).isFalse();
    }

    private double counter(String outcome) {
        return meters.get(MessagingMetrics.EVENT_PROCESSED)
                .tag(MessagingMetrics.TAG_OUTCOME, outcome)
                .counter().count();
    }

    private EventEnvelope<Map<String, Object>> envelope() {
        UUID eventId = ids.newUuid();
        return new EventEnvelope<>(eventId, "order.placed", 1, NOW, "order-service", eventId.toString(), null,
                Map.of("orderId", "o-1"));
    }
}
