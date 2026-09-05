package com.dawnline.dispatch.domain.optimizer;

import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.GANGNAM;
import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.START;
import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.YEOUIDO;
import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Ids;
import com.dawnline.common.Money;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 발행 직전 재검증 (ADR-026 분기 2) — revision 없이 경합 창을 닫는 유일한 자리.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PlanPrunerTest {

    private static Stop stopOf(OrderId... orderIds) {
        return new Stop(GANGNAM, List.of(orderIds), Parcel.EMPTY,
                OptimizerFixtures.window(), 90, 0);
    }

    private static PlannedRoute routeOf(List<Stop> stops) {
        List<PlannedStop> planned = new java.util.ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            planned.add(new PlannedStop(i + 1, stops.get(i), START.plusSeconds(600L * i),
                    START.plusSeconds(600L * i + 90)));
        }
        return new PlannedRoute(VehicleId.of(Ids.newId()), planned, 1_000, 600, Money.krw(50_000));
    }

    private static PlanResult resultOf(List<PlannedRoute> routes, List<Unassigned> unassigned) {
        int assigned = routes.stream().mapToInt(PlannedRoute::orderCount).sum();
        return new PlanResult(routes, unassigned, Money.krw(50_000L * routes.size()),
                new PlanMetrics(routes.size(), assigned, unassigned.size(), routes.size(),
                        1_000L * routes.size(), 600L * routes.size(), 0, 0, 1_234),
                List.of());
    }

    @Test
    void 취소가_없으면_그대로_돌려준다() {
        PlanResult result = resultOf(List.of(routeOf(List.of(stopOf(OrderId.of(Ids.newId()))))),
                List.of());

        assertThat(PlanPruner.prune(result, Set.of())).isSameAs(result);
    }

    @Test
    void 취소된_주문만_stop_에서_뺀다() {
        OrderId kept = OrderId.of(Ids.newId());
        OrderId cancelled = OrderId.of(Ids.newId());
        PlanResult result = resultOf(List.of(routeOf(List.of(stopOf(kept, cancelled)))), List.of());

        PlanResult pruned = PlanPruner.prune(result, Set.of(cancelled));

        assertThat(pruned.routes()).singleElement().satisfies(route ->
                assertThat(route.stops().getFirst().stop().orderIds()).containsExactly(kept));
    }

    @Test
    void 주문이_전부_취소된_stop_은_사라진다() {
        OrderId cancelled = OrderId.of(Ids.newId());
        OrderId kept = OrderId.of(Ids.newId());
        PlanResult result = resultOf(
                List.of(routeOf(List.of(stopOf(cancelled), stopOf(kept)))), List.of());

        PlanResult pruned = PlanPruner.prune(result, Set.of(cancelled));

        assertThat(pruned.routes()).singleElement()
                .satisfies(route -> assertThat(route.stops()).hasSize(1));
    }

    @Test
    void 순번을_1부터_다시_매긴다() {
        // 아직 아무도 보지 않았으므로 다시 매긴다 — 빈 자리를 남기면 route.assigned 의
        // "seq 는 1부터 연속" 불변식이 깨진다. 발행된 뒤라면 반대다 (§6.10).
        OrderId cancelled = OrderId.of(Ids.newId());
        PlanResult result = resultOf(List.of(routeOf(List.of(
                stopOf(OrderId.of(Ids.newId())), stopOf(cancelled),
                stopOf(OrderId.of(Ids.newId()))))), List.of());

        PlanResult pruned = PlanPruner.prune(result, Set.of(cancelled));

        assertThat(pruned.routes().getFirst().stops())
                .extracting(PlannedStop::seq).containsExactly(1, 2);
    }

    @Test
    void 주문이_전부_취소된_라우트는_사라진다() {
        OrderId cancelled = OrderId.of(Ids.newId());
        PlanResult result = resultOf(List.of(routeOf(List.of(stopOf(cancelled)))), List.of());

        assertThat(PlanPruner.prune(result, Set.of(cancelled)).routes()).isEmpty();
    }

    @Test
    void 취소된_주문은_미배정_목록에서도_빠진다() {
        // 취소된 주문에 미배정 페널티를 물리면 그 계획이 실제보다 비싸 보인다.
        OrderId cancelled = OrderId.of(Ids.newId());
        OrderId kept = OrderId.of(Ids.newId());
        PlanResult result = resultOf(List.of(routeOf(List.of(stopOf(OrderId.of(Ids.newId()))))),
                List.of(new Unassigned(cancelled, "r", "이유"), new Unassigned(kept, "r", "이유")));

        PlanResult pruned = PlanPruner.prune(result, Set.of(cancelled));

        assertThat(pruned.unassigned()).extracting(Unassigned::orderId).containsExactly(kept);
        assertThat(pruned.metrics().unassignedOrders()).isEqualTo(1);
    }

    @Test
    void 지표를_다시_센다() {
        OrderId cancelled = OrderId.of(Ids.newId());
        PlanResult result = resultOf(List.of(
                routeOf(List.of(stopOf(cancelled))),
                routeOf(List.of(stopOf(OrderId.of(Ids.newId()))))), List.of());

        PlanResult pruned = PlanPruner.prune(result, Set.of(cancelled));

        assertThat(pruned.metrics().routeCount()).isEqualTo(1);
        assertThat(pruned.metrics().assignedOrders()).isEqualTo(1);
        assertThat(pruned.metrics().planDurationMs())
                .as("계획에 걸린 시간은 바뀌지 않는다").isEqualTo(1_234);
    }
}
