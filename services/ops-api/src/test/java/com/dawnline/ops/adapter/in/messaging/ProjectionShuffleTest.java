package com.dawnline.ops.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.contract.EventContracts;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.ops.adapter.in.messaging.ProjectionScenario.Event;
import com.dawnline.ops.application.ReadModelProjector;
import com.dawnline.ops.support.InMemoryProcessedEvents;
import com.dawnline.ops.support.InMemoryReadModel;
import com.dawnline.ops.support.RowDiff;
import com.dawnline.ops.support.Shuffles;
import com.dawnline.ops.support.StubTransactions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 프로젝션은 순서와 무관하다 — 메모리 판 (ADR-051 결정 6).
 *
 * <p>같은 시나리오를 {@code ProjectionShuffleIT} 가 실제 PostgreSQL 에서 돌린다. 여기는 그 IT 의
 * 빠른 사촌이다 — SQL 과 잠금을 빼고 <strong>판정</strong>만 본다. 그래서 회차를 더 많이 돈다.
 * 레코드는 Kafka 가 부르는 것과 같은 리스너 메서드로 들어간다({@link ListenerTopics#deliver}).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ProjectionShuffleTest {

    private static final EventContracts CONTRACTS = EventContracts.load();
    private static final int ROUNDS = 300;
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    private final ProjectionScenario scenario = new ProjectionScenario(CONTRACTS);

    @Test
    void 시나리오는_구독하는_토픽을_전부_쓴다_뺀_것만_빼고() {
        Set<String> expected = new TreeSet<>(ListenerTopics.of().keySet());
        expected.removeAll(ProjectionScenario.EXCLUDED_TOPICS.keySet());

        Set<String> used = scenario.causalOrder().stream().map(Event::topic)
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(used)
                .as("토픽이 붙었는데 시나리오가 그것을 쓰지 않으면 그 토픽은 순서 검사 밖이다 — "
                        + "ADR-050 의 열한 번째가 바로 그 경우였을 것이다")
                .isEqualTo(expected);
    }

    @Test
    void 뺀_토픽은_구독하는_토픽이고_이유를_말한다() {
        ProjectionScenario.EXCLUDED_TOPICS.forEach((topic, reason) -> {
            assertThat(ListenerTopics.of()).as("뺀 토픽 %s 은 구독하는 토픽이어야 한다", topic).containsKey(topic);
            assertThat(reason).as("%s 을 뺀 이유", topic).isNotBlank();
        });
    }

    @Test
    void 인과_순서의_결과가_시나리오가_말하는_행이다() {
        InMemoryReadModel model = replay(scenario.causalOrder());

        // O4 — 취소됐는데 배송됐다. 두 칸의 조합이 운영자의 예외 목록이다(§5.5 「DDL 정정」).
        assertThat(model.order(scenario.o4))
                .containsEntry("order_status", "CANCELLED")
                .containsEntry("delivery_outcome", "COMPLETED")
                .containsEntry("delivered_at", scenario.o4Delivered);
        // O2 — 재계획이 R2 로 옮겼다. 계획 칸은 새 계획, ETA 는 뒤의 at-risk, 결과는 실패.
        assertThat(model.order(scenario.o2))
                .containsEntry("order_status", "DISPATCHED")
                .containsEntry("delivery_outcome", "FAILED")
                .containsEntry("route_id", scenario.r2)
                .containsEntry("planned_arrival", scenario.o2ArrivalOnR2)
                .containsEntry("eta_at", scenario.o2EtaSecond)
                .containsEntry("promised_end_original", scenario.promisedEnd)
                .containsEntry("promised_end_revised", scenario.revisedEnd)
                .doesNotContainKey("delivered_at");
        // O5 — 배차 불가. 캠프·웨이브는 끝까지 비어 있다(부재는 값이 아니다).
        assertThat(model.order(scenario.o5))
                .containsEntry("order_status", "UNSERVICEABLE")
                .doesNotContainKeys("camp_id", "wave_id", "route_id", "delivery_outcome");
        assertThat(model.wave(scenario.waveId))
                .containsEntry("status", "PLANNED")
                .containsEntry("order_count", 4L)
                .containsEntry("plan_id", scenario.planId)
                .containsEntry("route_count", 2);
        assertThat(model.route(scenario.r1))
                .containsEntry("revision", 2)
                .containsEntry("status", "DEPARTED")
                .containsEntry("departed_at", scenario.r1Departed)
                .containsEntry("completed_count", 1L)
                .containsEntry("failed_count", 0L)
                .containsEntry("at_risk", true)
                .containsEntry("stop_count", 1);
        assertThat(model.route(scenario.r2))
                .containsEntry("revision", 2)
                .containsEntry("completed_count", 2L)
                .containsEntry("failed_count", 1L)
                .containsEntry("stop_count", 3);
    }

    @Test
    void 씨_고정_셔플의_최종_행이_인과_순서의_최종_행과_같다() {
        List<Event> causal = scenario.causalOrder();
        var baseline = replay(causal).snapshot();

        for (long seed = 1; seed <= ROUNDS; seed++) {
            List<Event> shuffled = Shuffles.of(causal, seed);
            // 전제: 이 회차가 실제로 순서를 바꿨다. 항등 순열은 아무것도 검사하지 않는다(결정 6 의 4).
            assertThat(shuffled).as("씨 %d 의 셔플이 인과 순서와 달라야 한다", seed).isNotEqualTo(causal);

            assertThat(RowDiff.between(baseline, replay(shuffled).snapshot(), scenario::name))
                    .as("씨 %d — 순서: %s", seed, shuffled)
                    .isEmpty();
        }
    }

    @Test
    void 같은_씨는_같은_순서를_만든다() {
        // 깨졌을 때 실패 메시지의 씨 하나로 그 순서를 다시 만들 수 있어야 한다(불변규칙 12).
        assertThat(Shuffles.of(scenario.causalOrder(), 7)).isEqualTo(Shuffles.of(scenario.causalOrder(), 7));
    }

    private static InMemoryReadModel replay(List<Event> order) {
        InMemoryReadModel model = new InMemoryReadModel();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ProjectionListener listener = new ProjectionListener(
                new IdempotentConsumer(new InMemoryProcessedEvents(), StubTransactions.committing(), meters, CLOCK),
                new ReadModelProjector(model.orderRows(), model.routeRows(), model.waveRows(), CLOCK),
                EventJson.standard(), meters);
        long offset = 0;
        for (Event event : order) {
            ListenerTopics.deliver(listener, new ConsumerRecord<>(event.topic(), 0, offset++, event.key(), event.value()));
        }
        return model;
    }
}
