package com.dawnline.ops.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.messaging.MessagingMetrics;
import com.dawnline.messaging.contract.EventContracts;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.ops.application.port.in.Fact;
import com.dawnline.ops.application.port.in.ProjectFactUseCase;
import com.dawnline.ops.application.port.in.ProjectFactUseCase.Projection;
import com.dawnline.ops.support.InMemoryProcessedEvents;
import com.dawnline.ops.support.StubTransactions;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.PlatformTransactionManager;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ProjectionListenerTest {

    private static final EventContracts CONTRACTS = EventContracts.load();
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<Fact> projected = new ArrayList<>();

    static Stream<Path> examples() {
        return CONTRACTS.examples().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("examples")
    void 계약_예시가_전부_사실로_열린다(Path example) {
        // 예시는 봉투로 감싼 레코드 value 전체다. 그것이 리스너를 지나 사실이 되는지 — 계약의 필수
        // 필드를 이 소비자가 전부 읽을 수 있는지 — 를 예시마다 본다. 예시를 열거하지 않는다.
        String value = CONTRACTS.readTree(example).toString();
        String eventType = CONTRACTS.readTree(example).get("eventType").asString();
        ProjectionListener listener = listener(StubTransactions.committing(), fact -> Projection.CLEAN);

        ListenerTopics.deliver(listener, record("dawnline." + eventType + ".v1", value));

        assertThat(projected).hasSize(1);
    }

    @Test
    void stale_은_커밋_뒤에_센다() {
        ProjectionListener listener = listener(StubTransactions.committing(), fact -> new Projection(2));

        ListenerTopics.deliver(listener, placed());

        assertThat(stale()).isEqualTo(2.0);
    }

    @Test
    void 커밋에_실패하면_세지_않는다() {
        // 미터 레지스트리는 트랜잭션을 모른다. 반영 안에서 올리면 롤백된 반영의 숫자가 남는다(CLAUDE.md).
        ProjectionListener listener = listener(StubTransactions.failingOnCommit(), fact -> new Projection(2));

        assertThatThrownBy(() -> ListenerTopics.deliver(listener, placed())).isInstanceOf(IllegalStateException.class);

        assertThat(projected).as("반영은 실행됐다 — 커밋만 실패했다").hasSize(1);
        assertThat(stale()).isZero();
    }

    @Test
    void 같은_이벤트가_두_번_와도_한_번만_반영한다() {
        // 불변규칙 2. 멱등은 중복만 막는다 — 순서는 프로젝터의 몫이다(ProjectionShuffleTest).
        ProjectionListener listener = listener(StubTransactions.committing(), fact -> new Projection(1));
        ConsumerRecord<String, String> record = placed();

        ListenerTopics.deliver(listener, record);
        ListenerTopics.deliver(listener, record);

        assertThat(projected).hasSize(1);
        assertThat(stale()).isEqualTo(1.0);
    }

    private ProjectionListener listener(PlatformTransactionManager transactions, ProjectFactUseCase projector) {
        return new ProjectionListener(
                new IdempotentConsumer(new InMemoryProcessedEvents(), transactions, meters, CLOCK),
                fact -> {
                    projected.add(fact);
                    return projector.project(fact);
                },
                EventJson.standard(), meters);
    }

    private ConsumerRecord<String, String> placed() {
        Path example = CONTRACTS.contractsDirectory().resolve("examples/order.placed.v1.example.json");
        return record(ProjectionListener.ORDER_PLACED_TOPIC, CONTRACTS.readTree(example).toString());
    }

    private static ConsumerRecord<String, String> record(String topic, String value) {
        return new ConsumerRecord<>(topic, 0, 0L, "key", value);
    }

    private double stale() {
        Counter counter = meters.find(MessagingMetrics.EVENT_STALE)
                .tag(MessagingMetrics.TAG_CONSUMER, ProjectionListener.CONSUMER).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
