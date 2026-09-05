package com.dawnline.dispatch.domain.optimizer;

import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.GANGNAM;
import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.START;
import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.YEOUIDO;
import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Ids;
import com.dawnline.common.Money;
import com.dawnline.common.TimeWindow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * §6.5 6단계 — 개선 단계 버그 방어선.
 *
 * <p>여기서 보는 것은 "룰이 맞나" 가 아니라 <strong>"최종 산출물이 룰을 지키나"</strong> 다.
 * 배치할 때 검사했더라도 2-opt·relocate 가 순서를 바꾸면 도착 시각이 바뀌고, 그러면 시간 관련
 * 하드 룰의 판정이 바뀐다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PlanValidatorTest {

    private final PlanValidator validator = new PlanValidator();
    private final DistanceProvider distance = OptimizerFixtures.distance();

    /** 누적 적재가 용량을 넘으면 막는다 (§6.3 VEHICLE_CAPACITY). */
    private record CapacityRule(String name, int priority) implements HardRule {

        @Override
        public Feasibility check(Stop stop, VehicleSpec vehicle, RouteState state) {
            Parcel after = state.load().plus(stop.parcel());
            return vehicle.capacity().admits(after)
                    ? Feasibility.ok()
                    : Feasibility.violated(name, "용량 초과: " + after.weightG() + "g");
        }
    }

    /** stop 수 상한 (§6.3 MAX_STOPS_PER_ROUTE). */
    private record MaxStopsRule(String name, int priority, int max) implements HardRule {

        @Override
        public Feasibility check(Stop stop, VehicleSpec vehicle, RouteState state) {
            return state.stopCount() < max
                    ? Feasibility.ok()
                    : Feasibility.violated(name, "stop 상한 " + max + " 초과");
        }
    }

    private VehicleSpec vehicleWithCapacity(int maxWeightG) {
        return new VehicleSpec(VehicleId.of(Ids.newId()),
                new Capacity(maxWeightG, 10_000_000),
                new VehicleAttrs("VAN", false, false),
                new TimeWindow(START, START.plus(Duration.ofHours(8))),
                VehicleCost.krw(30_000, 500, 200));
    }

    private PlannedRoute routeOf(VehicleSpec vehicle, CampDepot depot, List<Stop> stops) {
        RouteState state = RouteState.empty(vehicle, depot, distance, START);
        for (Stop stop : stops) {
            state = state.append(stop);
        }
        return new PlannedRoute(vehicle.id(), state.stops(), state.distanceM(), 0, Money.ZERO);
    }

    private PlanResult resultOf(PlannedRoute route) {
        return new PlanResult(List.of(route), List.of(), Money.ZERO,
                new PlanMetrics(1, route.orderCount(), 0, 1, route.distanceM(), 0, 0, 0, 0),
                List.of());
    }

    @Test
    void 하드_룰을_지킨_라우트는_위반이_없다() {
        VehicleSpec vehicle = vehicleWithCapacity(10_000);
        CampDepot depot = OptimizerFixtures.depot();
        List<Stop> stops = List.of(
                OptimizerFixtures.stop(GANGNAM, new Parcel(1_000, 100, false, false), 0),
                OptimizerFixtures.stop(YEOUIDO, new Parcel(1_000, 100, false, false), 0));
        PlannedRoute route = routeOf(vehicle, depot, stops);

        PlanningProblem problem = problem(depot, vehicle, new CapacityRule("capacity", 20));

        assertThat(validator.validate(problem, resultOf(route))).isEmpty();
    }

    @Test
    void 누적_용량을_넘긴_라우트를_잡는다() {
        // 마지막 상태만 보면 "3번째 stop 에서 이미 넘었다" 를 볼 수 없다. 그래서 처음부터 재생한다.
        VehicleSpec vehicle = vehicleWithCapacity(2_500);
        CampDepot depot = OptimizerFixtures.depot();
        List<Stop> stops = List.of(
                OptimizerFixtures.stop(GANGNAM, new Parcel(1_000, 100, false, false), 0),
                OptimizerFixtures.stop(YEOUIDO, new Parcel(1_000, 100, false, false), 0),
                OptimizerFixtures.stop(GANGNAM, new Parcel(1_000, 100, false, false), 0));
        PlannedRoute route = routeOf(vehicle, depot, stops);

        PlanningProblem problem = problem(depot, vehicle, new CapacityRule("capacity", 20));

        assertThat(validator.validate(problem, resultOf(route)))
                .singleElement()
                .satisfies(violation -> {
                    assertThat(violation.seq()).as("세 번째 stop 에서 넘었다").isEqualTo(3);
                    assertThat(violation.feasibility().ruleName()).isEqualTo("capacity");
                });
    }

    @Test
    void stop_상한을_넘긴_라우트를_잡는다() {
        VehicleSpec vehicle = vehicleWithCapacity(10_000);
        CampDepot depot = OptimizerFixtures.depot();
        List<Stop> stops = List.of(
                OptimizerFixtures.stop(GANGNAM), OptimizerFixtures.stop(YEOUIDO),
                OptimizerFixtures.stop(GANGNAM));
        PlannedRoute route = routeOf(vehicle, depot, stops);

        PlanningProblem problem = problem(depot, vehicle, new MaxStopsRule("max-stops", 10, 2));

        assertThat(validator.validate(problem, resultOf(route)))
                .singleElement()
                .satisfies(violation -> assertThat(violation.seq()).isEqualTo(3));
    }

    @Test
    void 계획에_없는_차량이_배정되면_라우트_단위로_잡는다() {
        VehicleSpec vehicle = vehicleWithCapacity(10_000);
        CampDepot depot = OptimizerFixtures.depot();
        PlannedRoute route = routeOf(vehicle, depot, List.of(OptimizerFixtures.stop(GANGNAM)));

        // 문제에는 다른 차량만 들어 있다.
        PlanningProblem problem = problem(depot, vehicleWithCapacity(10_000),
                new CapacityRule("capacity", 20));

        assertThat(validator.validate(problem, resultOf(route)))
                .singleElement()
                .satisfies(violation -> {
                    assertThat(violation.seq()).as("stop 이 아니라 라우트의 문제다").isZero();
                    assertThat(violation.feasibility().ruleName()).isEqualTo("unknown-vehicle");
                });
    }

    @Test
    void 룰이_없으면_어떤_라우트도_통과한다() {
        VehicleSpec vehicle = vehicleWithCapacity(1);
        CampDepot depot = OptimizerFixtures.depot();
        PlannedRoute route = routeOf(vehicle, depot,
                List.of(OptimizerFixtures.stop(GANGNAM, new Parcel(999_999, 1, false, false), 0)));

        PlanningProblem problem = new PlanningProblem(OptimizerFixtures.wave(), depot, List.of(),
                List.of(vehicle), RuleSet.empty(), new CostModel(), distance,
                new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(5)), START, 1L);

        assertThat(validator.validate(problem, resultOf(route))).isEmpty();
    }

    private PlanningProblem problem(CampDepot depot, VehicleSpec vehicle, HardRule rule) {
        return new PlanningProblem(OptimizerFixtures.wave(), depot, List.of(), List.of(vehicle),
                RuleSet.of(List.of(rule), 1), new CostModel(), distance,
                new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(5)),
                Instant.from(START), 1L);
    }
}
