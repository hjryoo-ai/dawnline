package com.dawnline.dispatch.domain.optimizer.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.Money;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.domain.optimizer.CampDepot;
import com.dawnline.dispatch.domain.optimizer.Candidate;
import com.dawnline.dispatch.domain.optimizer.Capacity;
import com.dawnline.dispatch.domain.optimizer.CostModel;
import com.dawnline.dispatch.domain.optimizer.Explanation;
import com.dawnline.dispatch.domain.optimizer.HaversineDistance;
import com.dawnline.dispatch.domain.optimizer.OrderId;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.PlanResult;
import com.dawnline.dispatch.domain.optimizer.PlanValidator;
import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import com.dawnline.dispatch.domain.optimizer.PlannedStop;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class BaselineNearestNeighborTest {

    private static final Instant START = Instant.parse("2026-09-06T01:00:00Z");
    private static final GeoPoint CAMP = GeoPoint.of(37.5663, 126.9779);
    /** 캠프에서 가까운 순서: NEAR → MID → FAR. */
    private static final GeoPoint NEAR = GeoPoint.of(37.5700, 126.9779);
    private static final GeoPoint MID = GeoPoint.of(37.5800, 126.9779);
    private static final GeoPoint FAR = GeoPoint.of(37.6000, 126.9779);

    private static final TimeWindow WINDOW =
            new TimeWindow(START, START.plus(Duration.ofHours(6)));
    private static final UUID CAMP_ID = Ids.newId();

    private final BaselineNearestNeighbor strategy = new BaselineNearestNeighbor();

    private static Candidate order(GeoPoint point, Parcel parcel) {
        return new Candidate(OrderId.of(Ids.newId()), point, parcel, WINDOW, 60, 0);
    }

    private static VehicleSpec vehicle(String type, int maxWeightG, boolean cold) {
        return new VehicleSpec(VehicleId.of(Ids.newId()), new Capacity(maxWeightG, 10_000_000),
                new VehicleAttrs(type, cold, false),
                new TimeWindow(START, START.plus(Duration.ofHours(10))),
                VehicleCost.krw(30_000, 500, 200));
    }

    private static PlanningProblem problem(List<Candidate> candidates, List<VehicleSpec> vehicles,
            RuleSet rules) {
        return new PlanningProblem(new WaveRef(Ids.newId(), CAMP_ID, "SAME_DAY", START),
                new CampDepot(CAMP_ID, CAMP), candidates, vehicles, rules, new CostModel(),
                new HaversineDistance(1.3d, 25.0d),
                new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(3)), START, 1L);
    }

    private static RuleSet rules(RuleDefinition... definitions) {
        return DispatchRules.ruleSet(List.of(definitions), 1);
    }

    private static RuleDefinition definition(String name, RuleType type, RuleSeverity severity,
            int priority, Map<String, Object> params) {
        return new RuleDefinition(name, type, severity, priority, params);
    }

    @Test
    void 가까운_곳부터_간다() {
        // 입력 순서를 일부러 뒤집어 둔다 — 순서가 아니라 거리로 고르는지 본다.
        PlanningProblem problem = problem(
                List.of(order(FAR, Parcel.EMPTY), order(NEAR, Parcel.EMPTY), order(MID, Parcel.EMPTY)),
                List.of(vehicle("VAN", 1_000_000, false)), RuleSet.empty());

        PlanResult result = strategy.plan(problem);

        assertThat(result.routes()).singleElement().satisfies(route ->
                assertThat(route.stops()).extracting(planned -> planned.stop().point())
                        .containsExactly(NEAR, MID, FAR));
    }

    @Test
    void 통합된_지점은_한_번만_방문한다() {
        PlanningProblem problem = problem(
                List.of(order(NEAR, new Parcel(1_000, 1_000, false, false)),
                        order(NEAR, new Parcel(1_000, 1_000, false, false))),
                List.of(vehicle("VAN", 1_000_000, false)), RuleSet.empty());

        PlanResult result = strategy.plan(problem);

        assertThat(result.routes()).singleElement().satisfies(route -> {
            assertThat(route.stops()).hasSize(1);
            assertThat(route.orderCount()).isEqualTo(2);
        });
    }

    @Test
    void 용량이_차면_다음_차량으로_넘어간다() {
        PlanningProblem problem = problem(
                List.of(order(NEAR, new Parcel(600, 1, false, false)),
                        order(MID, new Parcel(600, 1, false, false))),
                List.of(vehicle("VAN", 1_000, false), vehicle("VAN", 1_000, false)),
                rules(definition("capacity", RuleType.VEHICLE_CAPACITY, RuleSeverity.HARD, 15, Map.of())));

        PlanResult result = strategy.plan(problem);

        assertThat(result.routes()).hasSize(2);
        assertThat(result.unassigned()).isEmpty();
    }

    @Test
    void 어느_차량도_못_실으면_사유와_함께_미배정이다() {
        PlanningProblem problem = problem(
                List.of(order(NEAR, new Parcel(1, 1, true, false))),
                List.of(vehicle("VAN", 1_000_000, false)),
                rules(definition("cold-chain", RuleType.VEHICLE_ATTRIBUTE_MATCH, RuleSeverity.HARD, 10,
                        Map.of("orderFlag", "requiresCold", "vehicleFlag", "isCold"))));

        PlanResult result = strategy.plan(problem);

        assertThat(result.routes()).isEmpty();
        assertThat(result.unassigned()).singleElement().satisfies(unassigned ->
                assertThat(unassigned.ruleName()).isEqualTo("cold-chain"));
    }

    @Test
    void 빈_라우트는_만들지_않는다() {
        // 굴리지 않은 차에 고정비를 물면 미배정보다 비싸 보인다.
        PlanningProblem problem = problem(
                List.of(order(NEAR, Parcel.EMPTY)),
                List.of(vehicle("VAN", 1_000_000, false), vehicle("VAN", 1_000_000, false)),
                RuleSet.empty());

        assertThat(strategy.plan(problem).routes()).hasSize(1);
    }

    @Test
    void 총비용은_라우트_비용과_미배정_페널티의_합이다() {
        RuleSet rules = rules(
                definition("cold-chain", RuleType.VEHICLE_ATTRIBUTE_MATCH, RuleSeverity.HARD, 10,
                        Map.of("orderFlag", "requiresCold", "vehicleFlag", "isCold")),
                definition("unassigned", RuleType.UNASSIGNED_PENALTY, RuleSeverity.SOFT, 900,
                        Map.of("baseKrw", 30_000, "perPriorityKrw", 0)));
        PlanningProblem problem = problem(
                List.of(order(NEAR, Parcel.EMPTY), order(MID, new Parcel(1, 1, true, false))),
                List.of(vehicle("VAN", 1_000_000, false)), rules);

        PlanResult result = strategy.plan(problem);

        Money routeCost = result.routes().stream().map(PlannedRoute::cost)
                .reduce(Money.ZERO, Money::plus);
        assertThat(result.totalCost()).isEqualTo(routeCost.plus(Money.krw(30_000)));
    }

    @Test
    void 배정과_미배정_모두_설명을_남긴다() {
        PlanningProblem problem = problem(
                List.of(order(NEAR, Parcel.EMPTY), order(MID, new Parcel(1, 1, true, false))),
                List.of(vehicle("VAN", 1_000_000, false)),
                rules(definition("cold-chain", RuleType.VEHICLE_ATTRIBUTE_MATCH, RuleSeverity.HARD, 10,
                        Map.of("orderFlag", "requiresCold", "vehicleFlag", "isCold"))));

        PlanResult result = strategy.plan(problem);

        assertThat(result.explanations()).hasSize(2)
                .extracting(Explanation::outcome)
                .containsExactlyInAnyOrder(Explanation.Outcome.ASSIGNED,
                        Explanation.Outcome.UNASSIGNED);
    }

    @Test
    void 결과가_하드_룰을_지킨다() {
        // 전략이 배치 시점에 검사한 것과 최종 산출물이 같아야 한다 — 캐시가 답을 바꾼 적이 있다.
        RuleSet rules = rules(
                definition("capacity", RuleType.VEHICLE_CAPACITY, RuleSeverity.HARD, 15, Map.of()),
                definition("max-stops", RuleType.MAX_STOPS_PER_ROUTE, RuleSeverity.HARD, 20,
                        Map.of("max", 2)),
                definition("shift", RuleType.SHIFT_WINDOW, RuleSeverity.HARD, 25,
                        Map.of("bufferMinutes", 30)),
                definition("late-limit", RuleType.TIME_WINDOW_LIMIT, RuleSeverity.HARD, 30,
                        Map.of("hardLimitMinutes", 60)));
        PlanningProblem problem = problem(
                List.of(order(NEAR, Parcel.EMPTY), order(MID, Parcel.EMPTY), order(FAR, Parcel.EMPTY)),
                List.of(vehicle("VAN", 1_000_000, false), vehicle("VAN", 1_000_000, false)), rules);

        PlanResult result = strategy.plan(problem);

        assertThat(new PlanValidator().validate(problem, result)).isEmpty();
    }

    @Test
    void 도착_시각이_순서대로_흐른다() {
        PlanningProblem problem = problem(
                List.of(order(NEAR, Parcel.EMPTY), order(MID, Parcel.EMPTY), order(FAR, Parcel.EMPTY)),
                List.of(vehicle("VAN", 1_000_000, false)), RuleSet.empty());

        List<PlannedStop> stops = strategy.plan(problem).routes().getFirst().stops();

        assertThat(stops.get(0).departure()).isBeforeOrEqualTo(stops.get(1).arrival());
        assertThat(stops.get(1).departure()).isBeforeOrEqualTo(stops.get(2).arrival());
    }

    @Test
    void 같은_입력이면_같은_결과다() {
        PlanningProblem problem = problem(
                List.of(order(FAR, Parcel.EMPTY), order(NEAR, Parcel.EMPTY), order(MID, Parcel.EMPTY)),
                List.of(vehicle("VAN", 1_000_000, false)), RuleSet.empty());

        assertThat(strategy.plan(problem).totalCost())
                .isEqualTo(new BaselineNearestNeighbor().plan(problem).totalCost());
    }

    @Test
    void 이름이_설계서와_같다() {
        assertThat(strategy.name()).isEqualTo("baseline-nn");
    }
}
