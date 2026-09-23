package com.dawnline.ops.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.contract.EventContracts;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.ops.adapter.in.messaging.ProjectionScenario.Event;
import com.dawnline.ops.application.ReadModelProjector;
import com.dawnline.ops.application.port.out.ColumnFamily;
import com.dawnline.ops.application.port.out.OrderColumn;
import com.dawnline.ops.application.port.out.RouteColumn;
import com.dawnline.ops.support.InMemoryProcessedEvents;
import com.dawnline.ops.support.InMemoryReadModel;
import com.dawnline.ops.support.Shuffles;
import com.dawnline.ops.support.StubTransactions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 계획 칸과 추적 칸이 섞이지 않는가 (DESIGN.md §5.5 「판정 키」, ADR-047 을 읽기 모델로).
 *
 * <p>한 행에 두 계열이 같이 앉는다 — 주문 행에 계획 도착과 배송 결과, 라우트 행에 계획 거리와
 * 출발 시각. 두 계열은 판정 키가 다르다: 계획은 {@code revision}(라우트를 넘으면 계획 이벤트의
 * {@code occurredAt}), 추적은 그 사실의 사건 시각이나 추적 축. 그래서 두 가지를 본다.
 * <ol>
 *   <li>토픽은 자기 계열의 칸(과 키·축)만 쓴다 — 추적 사실이 계획 칸을 덮으면 그 칸의 판정 키가
 *       뜻을 잃는다.</li>
 *   <li>판정 키가 있는 칸은 판정 키와 <strong>같은 패치에서만</strong> 쓰인다 — 키 없이 쓰인 값은
 *       다음 사실이 무엇과 견줘야 할지 모른다.</li>
 * </ol>
 * 사실 집합은 순서 검사와 같은 시나리오이고, 조건부 쓰기까지 덮도록 인과 순서와 셔플 여럿을 돈다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ColumnFamilyTest {

    /** 토픽 → 그 토픽이 싣는 사실의 계열. 리스너가 구독하는 토픽 전부에 있어야 한다(아래 첫 검사). */
    static final Map<String, ColumnFamily> TOPIC_FAMILY = Map.ofEntries(
            Map.entry(ProjectionListener.ORDER_PLACED_TOPIC, ColumnFamily.ORDER),
            Map.entry(ProjectionListener.FULFILLMENT_PLANNED_TOPIC, ColumnFamily.ORDER),
            Map.entry(ProjectionListener.ORDER_DISPATCHED_TOPIC, ColumnFamily.ORDER),
            Map.entry(ProjectionListener.ORDER_CANCELLED_TOPIC, ColumnFamily.ORDER),
            Map.entry(ProjectionListener.WAVE_CLOSED_TOPIC, ColumnFamily.ORDER),
            Map.entry(ProjectionListener.ROUTE_ASSIGNED_TOPIC, ColumnFamily.PLAN),
            Map.entry(ProjectionListener.PLAN_COMPLETED_TOPIC, ColumnFamily.PLAN),
            Map.entry(ProjectionListener.PLAN_FAILED_TOPIC, ColumnFamily.PLAN),
            Map.entry(ProjectionListener.DELIVERY_STATUS_TOPIC, ColumnFamily.TRACKING),
            Map.entry(ProjectionListener.DELIVERY_AT_RISK_TOPIC, ColumnFamily.TRACKING),
            Map.entry(ProjectionListener.ROUTE_DEPARTED_TOPIC, ColumnFamily.TRACKING));

    /**
     * 판정 키가 있는 칸 → 그 판정 키. 웨이브의 계획 칸은 여기 없다 — {@code plan.completed} 는 웨이브에
     * 한 번만 온다(ADR-024, 재계획은 다시 내지 않는다). 견줄 둘째 값이 없다.
     */
    static final Map<Enum<?>, Enum<?>> GUARDED = Map.ofEntries(
            Map.entry(OrderColumn.ROUTE_ID, OrderColumn.PLANNED_AS_OF),
            Map.entry(OrderColumn.PLANNED_ARRIVAL, OrderColumn.PLANNED_AS_OF),
            Map.entry(OrderColumn.ETA_AT, OrderColumn.ETA_AS_OF),
            Map.entry(RouteColumn.PLAN_ID, RouteColumn.REVISION),
            Map.entry(RouteColumn.VEHICLE_ID, RouteColumn.REVISION),
            Map.entry(RouteColumn.DRIVER_ID, RouteColumn.REVISION),
            Map.entry(RouteColumn.STOP_COUNT, RouteColumn.REVISION),
            Map.entry(RouteColumn.DISTANCE_M, RouteColumn.REVISION),
            Map.entry(RouteColumn.COST_KRW, RouteColumn.REVISION),
            Map.entry(RouteColumn.PLANNED_DEPARTURE, RouteColumn.REVISION));

    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    private static final int SHUFFLES = 20;

    private final ProjectionScenario scenario = new ProjectionScenario(EventContracts.load());

    @Test
    void 구독하는_토픽마다_계열이_있다() {
        // 빼는 방식이 아니라 대조다 — 토픽이 붙으면 그 토픽이 어느 계열의 사실인지 적을 때까지 빨갛다.
        assertThat(new TreeSet<>(TOPIC_FAMILY.keySet())).isEqualTo(new TreeSet<>(ListenerTopics.of().keySet()));
    }

    @Test
    void 라우트의_계획_칸은_전부_revision_이_판정한다() {
        // 칸이 늘었을 때 GUARDED 에 넣는 것을 잊지 않게 — 계획 칸 전부에서 판정 키와 키 칸을 뺀 것.
        Set<RouteColumn> plan = EnumSet.noneOf(RouteColumn.class);
        for (RouteColumn column : RouteColumn.values()) {
            if (column.family() == ColumnFamily.PLAN && column != RouteColumn.REVISION) {
                plan.add(column);
            }
        }
        assertThat(plan).allSatisfy(column -> assertThat(GUARDED).containsEntry(column, RouteColumn.REVISION));
    }

    @Test
    void 토픽은_자기_계열의_칸과_키와_축만_쓴다() {
        for (List<Event> order : orders()) {
            replay(order, (event, columns) -> {
                ColumnFamily own = TOPIC_FAMILY.get(event.topic());
                assertThat(columns).as("%s 가 쓴 칸", event)
                        .allSatisfy(column -> assertThat(familyOf(column))
                                .as("%s.%s", column.getDeclaringClass().getSimpleName(), column)
                                .isIn(own, ColumnFamily.KEY, ColumnFamily.AXIS));
            });
        }
    }

    @Test
    void 판정_키가_있는_칸은_판정_키와_같은_패치에서만_쓰인다() {
        for (List<Event> order : orders()) {
            replay(order, (event, columns) -> GUARDED.forEach((guarded, key) -> {
                if (columns.contains(guarded)) {
                    assertThat(columns).as("%s 가 %s 를 %s 없이 썼다", event, guarded, key).contains(key);
                }
            }));
        }
    }

    private List<List<Event>> orders() {
        List<List<Event>> orders = new ArrayList<>();
        orders.add(scenario.causalOrder());
        for (long seed = 1; seed <= SHUFFLES; seed++) {
            orders.add(Shuffles.of(scenario.causalOrder(), seed));
        }
        return orders;
    }

    private interface WriteCheck {
        void check(Event event, Set<Enum<?>> columns);
    }

    private static void replay(List<Event> order, WriteCheck check) {
        InMemoryReadModel model = new InMemoryReadModel();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ProjectionListener listener = new ProjectionListener(
                new IdempotentConsumer(new InMemoryProcessedEvents(), StubTransactions.committing(), meters, CLOCK),
                new ReadModelProjector(model.orderRows(), model.routeRows(), model.waveRows(), CLOCK),
                EventJson.standard(), meters);
        long offset = 0;
        for (Event event : order) {
            ListenerTopics.deliver(listener, new ConsumerRecord<>(event.topic(), 0, offset++, event.key(), event.value()));
            for (Set<Enum<?>> columns : model.drainWrites()) {
                check.check(event, columns);
            }
        }
    }

    private static ColumnFamily familyOf(Enum<?> column) {
        return switch (column) {
            case OrderColumn c -> c.family();
            case RouteColumn c -> c.family();
            case com.dawnline.ops.application.port.out.WaveColumn c -> c.family();
            default -> throw new IllegalStateException("모르는 칸: " + column);
        };
    }
}
