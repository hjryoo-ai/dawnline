package com.dawnline.dispatch.domain.optimizer.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.domain.PlanMode;
import com.dawnline.dispatch.domain.optimizer.CampDepot;
import com.dawnline.dispatch.domain.optimizer.Candidate;
import com.dawnline.dispatch.domain.optimizer.Capacity;
import com.dawnline.dispatch.domain.optimizer.ConstraintClass;
import com.dawnline.dispatch.domain.optimizer.CostModel;
import com.dawnline.dispatch.domain.optimizer.Explanation;
import com.dawnline.dispatch.domain.optimizer.HaversineDistance;
import com.dawnline.dispatch.domain.optimizer.OrderId;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.PlanResult;
import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import com.dawnline.dispatch.domain.optimizer.PlanningBudget;
import com.dawnline.dispatch.domain.optimizer.PlanningProblem;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import com.dawnline.dispatch.domain.optimizer.VehicleAttrs;
import com.dawnline.dispatch.domain.optimizer.VehicleCost;
import com.dawnline.dispatch.domain.optimizer.VehicleId;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import com.dawnline.dispatch.domain.optimizer.WaveRef;
import com.dawnline.dispatch.domain.optimizer.rule.DispatchRules;
import com.dawnline.dispatch.domain.optimizer.rule.RuleDefinition;
import com.dawnline.dispatch.domain.optimizer.rule.RuleSeverity;
import com.dawnline.dispatch.domain.optimizer.rule.RuleType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 좌석 예약이 <strong>계획 전체</strong>에서 하는 일 ([ADR-039], §6.5 3단계).
 *
 * <h2>재현하는 상황</h2>
 * {@code small} 의 한 줄이다 — <strong>냉장 ∧ 위험물을 실을 수 있는 차가 한 대뿐인데 그 차의
 * 자리가 전부 일반 수요로 찬다.</strong> 측정에서 그 차의 120 자리가 100% 일반 수요였고 조합
 * 수요 4건이 통째로 미배정이었다({@code docs/benchmarks/phase4-scarce-seats.md} §1).
 *
 * <p>여기서는 같은 형태를 작게 만든다: stop 18개 = 상한 6 × 차량 3대라 <strong>한 자리도
 * 남지 않는다.</strong> 조합 수요가 자리를 얻으려면 누군가 비켜 줘야 한다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("ScarceSeatPlan — 조합 차량의 자리는 그 조합의 것이다")
class ScarceSeatPlanTest {

    private static final Instant START = Instant.parse("2026-09-06T01:00:00Z");
    private static final GeoPoint CAMP = GeoPoint.of(37.5663, 126.9779);
    private static final java.util.UUID CAMP_ID = Ids.newId();
    private static final TimeWindow WINDOW = new TimeWindow(START, START.plus(Duration.ofHours(8)));
    private static final int STOP_CAP = 6;

    private final SweepGreedyNearestNeighbor strategy = SweepGreedyNearestNeighbor.withLocalSearch();

    private final VehicleSpec combo = vehicle(true, true);
    private final VehicleSpec cold = vehicle(true, false);
    private final VehicleSpec plain = vehicle(false, false);
    private final List<VehicleSpec> fleet = List.of(combo, cold, plain);

    @Test
    void 조합_차량의_자리는_그_조합의_수요에_남는다() {
        List<Candidate> orders = new ArrayList<>(ordinary(16));
        orders.add(at(200.0d, 3.0d, new Parcel(1, 1, true, true)));
        orders.add(at(210.0d, 3.2d, new Parcel(1, 1, true, true)));

        PlanResult result = strategy.plan(problem(orders));
        assertThat(result.unassigned())
                .as("자리는 정확히 18개이고 조합 수요 2건이 그중 둘을 받는다")
                .isEmpty();
        PlannedRoute route = routeOf(result, combo);
        assertThat(route.stops()).hasSize(STOP_CAP);
        assertThat(route.stops().stream()
                .filter(stop -> ConstraintClass.of(stop.stop()).equals(
                        new ConstraintClass(true, true)))
                .count())
                .as("예약한 두 자리가 실제로 그 조합에 갔다")
                .isEqualTo(2);
    }

    @Test
    void 예약_때문에_밀린_일반_stop_은_설명에_남는다() {
        List<Candidate> orders = new ArrayList<>(ordinary(16));
        orders.add(at(200.0d, 3.0d, new Parcel(1, 1, true, true)));
        orders.add(at(210.0d, 3.2d, new Parcel(1, 1, true, true)));

        PlanResult result = strategy.plan(problem(orders));

        assertThat(result.explanations())
                .as("「왜 이 주문이 이 차인가」에 예약이 답의 일부로 들어간다 (§6.3)")
                .anyMatch(explanation -> explanation.outcome() == Explanation.Outcome.ASSIGNED
                        && "reserved-seat".equals(explanation.ruleName()));
    }

    @Test
    void 아무도_앉지_못한_예약_좌석은_재삽입이_일반_수요로_채운다() {
        // 셋째 조합 수요는 <strong>어느 차의 용량도</strong> 넘는다 — 예약은 되지만 앉지 못한다.
        // 그 자리를 비워 두면 [ADR-038] 이 배운 「빈 좌석」의 교훈을 예약이 거꾸로 만든다.
        List<Candidate> orders = new ArrayList<>(ordinary(16));
        orders.add(at(200.0d, 3.0d, new Parcel(1, 1, true, true)));
        orders.add(at(210.0d, 3.2d, new Parcel(1, 1, true, true)));
        orders.add(at(220.0d, 3.4d, new Parcel(9_000_000, 1, true, true)));

        PlanResult result = strategy.plan(problem(orders));

        assertThat(result.unassigned())
                .as("전제: 앉을 수 없는 것은 그 한 건뿐이다")
                .hasSize(1);
        assertThat(result.routes().stream().mapToInt(route -> route.stops().size()).sum())
                .as("나머지 18개가 18 자리를 전부 쓴다 — 예약된 채 비는 자리가 없다")
                .isEqualTo(18);
        assertThat(routeOf(result, combo).stops()).hasSize(STOP_CAP);
    }

    private static PlannedRoute routeOf(PlanResult result, VehicleSpec vehicle) {
        return result.routes().stream()
                .filter(route -> route.vehicle().equals(vehicle.id()))
                .findFirst().orElseThrow(() ->
                        new AssertionError("차량 " + vehicle.id() + " 의 라우트가 없습니다"));
    }

    private static List<Candidate> ordinary(int count) {
        List<Candidate> orders = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            orders.add(at(i * 22.0d, 1.0d + i * 0.05d, Parcel.EMPTY));
        }
        return orders;
    }

    private static Candidate at(double degrees, double km, Parcel parcel) {
        double radians = Math.toRadians(degrees);
        GeoPoint point = GeoPoint.of(CAMP.lat() + 0.009d * km * Math.cos(radians),
                CAMP.lng() + 0.009d * km * Math.sin(radians));
        return new Candidate(OrderId.of(Ids.newId()), point, parcel, WINDOW, 60, 0);
    }

    private static VehicleSpec vehicle(boolean isCold, boolean hazmat) {
        return new VehicleSpec(VehicleId.of(Ids.newId()), new Capacity(1_000_000, 10_000_000),
                new VehicleAttrs("VAN", isCold, hazmat),
                new TimeWindow(START, START.plus(Duration.ofHours(10))),
                VehicleCost.krw(45_000, 600, 250));
    }

    private PlanningProblem problem(List<Candidate> candidates) {
        RuleSet rules = DispatchRules.ruleSet(List.of(
                new RuleDefinition("cold-chain", RuleType.VEHICLE_ATTRIBUTE_MATCH,
                        RuleSeverity.HARD, 10,
                        Map.of("orderFlag", "requiresCold", "vehicleFlag", "isCold")),
                new RuleDefinition("hazmat", RuleType.VEHICLE_ATTRIBUTE_MATCH, RuleSeverity.HARD,
                        11, Map.of("orderFlag", "hazmat", "vehicleFlag", "allowsHazmat")),
                new RuleDefinition("capacity", RuleType.VEHICLE_CAPACITY, RuleSeverity.HARD, 15,
                        Map.of()),
                new RuleDefinition("max-stops", RuleType.MAX_STOPS_PER_ROUTE, RuleSeverity.HARD, 20,
                        Map.of("max", STOP_CAP)),
                new RuleDefinition("unassigned", RuleType.UNASSIGNED_PENALTY, RuleSeverity.SOFT,
                        900, Map.of("baseKrw", 30_000, "perPriorityKrw", 20_000))), 1);

        return new PlanningProblem(new WaveRef(Ids.newId(), CAMP_ID, "SAME_DAY", START),
                new CampDepot(CAMP_ID, CAMP), candidates, fleet, rules, new CostModel(),
                new HaversineDistance(1.3d, 25.0d),
                new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(3)), PlanMode.FULL,
                1.0d, START, 1L);
    }
}
