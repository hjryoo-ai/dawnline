package com.dawnline.dispatch.domain.optimizer.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.domain.PlanMode;
import com.dawnline.dispatch.domain.optimizer.CampDepot;
import com.dawnline.dispatch.domain.optimizer.Candidate;
import com.dawnline.dispatch.domain.optimizer.Capacity;
import com.dawnline.dispatch.domain.optimizer.CostModel;
import com.dawnline.dispatch.domain.optimizer.DispatchStrategies;
import com.dawnline.dispatch.domain.optimizer.Explanation;
import com.dawnline.dispatch.domain.optimizer.HaversineDistance;
import com.dawnline.dispatch.domain.optimizer.OrderId;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.PlanResult;
import com.dawnline.dispatch.domain.optimizer.PlanValidator;
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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * {@code savings-cw+ls} 전략 전체 ([ADR-042], DESIGN.md §6.6).
 *
 * <p>비교표의 수치는 여기서 보지 않는다 — 그것은 {@code docs/benchmarks/phase4-savings-cw.md} 다.
 * 여기서 보는 것은 <strong>이 전략이 계획으로서 성립하는가</strong>다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("SavingsClarkeWright — 구성만 다르고 뒤 단계는 같은 비교 전략")
class SavingsClarkeWrightTest {

    private static final Instant START = Instant.parse("2026-09-06T01:00:00Z");
    private static final GeoPoint CAMP = GeoPoint.of(37.5663, 126.9779);
    private static final UUID CAMP_ID = Ids.newId();
    private static final TimeWindow WINDOW = new TimeWindow(START, START.plus(Duration.ofHours(8)));

    private final SavingsClarkeWright strategy = new SavingsClarkeWright();

    private static Candidate at(double degrees, double km, Parcel parcel) {
        double radians = Math.toRadians(degrees);
        GeoPoint point = GeoPoint.of(CAMP.lat() + 0.009d * km * Math.cos(radians),
                CAMP.lng() + 0.009d * km * Math.sin(radians));
        return new Candidate(OrderId.of(Ids.newId()), point, parcel, WINDOW, 60, 0);
    }

    private static VehicleSpec vehicle(String type, int maxWeightG, boolean cold) {
        return new VehicleSpec(VehicleId.of(Ids.newId()), new Capacity(maxWeightG, 100_000_000),
                new VehicleAttrs(type, cold, false),
                new TimeWindow(START, START.plus(Duration.ofHours(10))),
                VehicleCost.krw(45_000, 600, 250));
    }

    private static PlanningProblem problem(List<Candidate> candidates, List<VehicleSpec> vehicles,
            RuleSet rules) {
        return problem(candidates, vehicles, rules, PlanMode.FULL);
    }

    private static PlanningProblem problem(List<Candidate> candidates, List<VehicleSpec> vehicles,
            RuleSet rules, PlanMode mode) {
        return new PlanningProblem(new WaveRef(Ids.newId(), CAMP_ID, "SAME_DAY", START),
                new CampDepot(CAMP_ID, CAMP), candidates, vehicles, rules, new CostModel(),
                new HaversineDistance(1.3d, 25.0d),
                new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(3)), mode, 1.0d,
                START, 1L);
    }

    private static List<Candidate> fourSectors(int perSector) {
        List<Candidate> candidates = new ArrayList<>();
        for (double angle : new double[] {0, 90, 180, 270}) {
            for (int i = 0; i < perSector; i++) {
                candidates.add(at(angle + i * 0.5d, 1.0d + i * 0.1d, Parcel.EMPTY));
            }
        }
        return candidates;
    }

    @Test
    void 이름이_설계서와_같다() {
        assertThat(strategy.name()).isEqualTo("savings-cw+ls");
    }

    @Test
    void 내장_전략_목록에_있다() {
        // 목록이 하나뿐이어야 한다 — 벤치마크와 PlanRunner 가 같은 레지스트리를 본다(§6.6).
        assertThat(DispatchStrategies.contains(SavingsClarkeWright.NAME)).isTrue();
        assertThat(DispatchStrategies.create(SavingsClarkeWright.NAME).name())
                .isEqualTo(SavingsClarkeWright.NAME);
    }

    @Test
    void 결과가_하드_룰을_지킨다() {
        RuleSet rules = DispatchRules.ruleSet(List.of(
                new RuleDefinition("capacity", RuleType.VEHICLE_CAPACITY, RuleSeverity.HARD, 15,
                        Map.of()),
                new RuleDefinition("max-stops", RuleType.MAX_STOPS_PER_ROUTE, RuleSeverity.HARD, 20,
                        Map.of("max", 6)),
                new RuleDefinition("shift", RuleType.SHIFT_WINDOW, RuleSeverity.HARD, 25,
                        Map.of("bufferMinutes", 30)),
                new RuleDefinition("late-limit", RuleType.TIME_WINDOW_LIMIT, RuleSeverity.HARD, 30,
                        Map.of("hardLimitMinutes", 60))), 1);
        PlanningProblem problem = problem(fourSectors(5),
                List.of(vehicle("VAN", 60_000, false), vehicle("VAN", 60_000, false),
                        vehicle("VAN", 60_000, false), vehicle("VAN", 60_000, false)), rules);

        PlanResult result = strategy.plan(problem);

        assertThat(new PlanValidator().validate(problem, result)).isEmpty();
    }

    @Test
    void 모든_주문이_배정되거나_사유와_함께_남는다() {
        PlanningProblem problem = problem(fourSectors(4),
                List.of(vehicle("VAN", 10_000_000, false)), RuleSet.empty());

        PlanResult result = strategy.plan(problem);

        int assigned = result.routes().stream().mapToInt(PlannedRoute::orderCount).sum();
        assertThat(assigned + result.unassigned().size()).isEqualTo(16);
        assertThat(result.unassigned())
                .allSatisfy(unassigned -> assertThat(unassigned.reason()).isNotBlank());
    }

    @Test
    void 냉장_주문은_냉장차로_가고_사유가_남는다() {
        RuleSet rules = DispatchRules.ruleSet(List.of(new RuleDefinition("cold-chain",
                RuleType.VEHICLE_ATTRIBUTE_MATCH, RuleSeverity.HARD, 10,
                Map.of("orderFlag", "requiresCold", "vehicleFlag", "isCold"))), 1);
        PlanningProblem problem = problem(
                List.of(at(0, 1, new Parcel(1, 1, true, false))),
                List.of(vehicle("VAN", 10_000_000, false)), rules);

        PlanResult result = strategy.plan(problem);

        assertThat(result.unassigned()).singleElement().satisfies(unassigned ->
                assertThat(unassigned.ruleName()).isEqualTo("cold-chain"));
    }

    @Test
    void 빈_라우트는_만들지_않는다() {
        PlanningProblem problem = problem(List.of(at(0, 1, Parcel.EMPTY)),
                List.of(vehicle("VAN", 10_000_000, false), vehicle("VAN", 10_000_000, false),
                        vehicle("VAN", 10_000_000, false)),
                RuleSet.empty());

        assertThat(strategy.plan(problem).routes()).hasSize(1);
    }

    @Test
    void 배정된_주문마다_설명이_남는다() {
        PlanningProblem problem = problem(fourSectors(3),
                List.of(vehicle("VAN", 10_000_000, false), vehicle("VAN", 10_000_000, false)),
                RuleSet.empty());

        PlanResult result = strategy.plan(problem);

        assertThat(result.explanations()).hasSize(12)
                .allSatisfy(explanation -> assertThat(explanation.outcome())
                        .isEqualTo(Explanation.Outcome.ASSIGNED));
    }

    @Test
    void 통합된_지점은_한_번만_방문한다() {
        Candidate first = at(0, 1, new Parcel(1_000, 1, false, false));
        Candidate sameSpot = new Candidate(OrderId.of(Ids.newId()), first.point(),
                new Parcel(1_000, 1, false, false), WINDOW, 60, 0);
        PlanningProblem problem = problem(List.of(first, sameSpot),
                List.of(vehicle("VAN", 10_000_000, false)), RuleSet.empty());

        PlanResult result = strategy.plan(problem);

        assertThat(result.routes()).singleElement().satisfies(route -> {
            assertThat(route.stops()).hasSize(1);
            assertThat(route.orderCount()).isEqualTo(2);
        });
    }

    @Test
    void 같은_입력이면_같은_결과다() {
        PlanningProblem problem = problem(fourSectors(4),
                List.of(vehicle("VAN", 10_000_000, false), vehicle("VAN", 10_000_000, false)),
                RuleSet.empty());

        assertThat(strategy.plan(problem).totalCost())
                .isEqualTo(new SavingsClarkeWright().plan(problem).totalCost());
    }

    @Test
    void FAST_는_개선_단계만_생략한다() {
        // §6.7 의 열화는 전략 교체가 아니라 이 전략의 5단계를 끄는 것이다([ADR-034]). 이름은
        // 그대로여야 하고, 끈 결과는 켠 결과보다 싸질 수 없다.
        List<Candidate> orders = fourSectors(5);
        List<VehicleSpec> fleet = List.of(vehicle("VAN", 10_000_000, false),
                vehicle("VAN", 10_000_000, false));

        long full = strategy.plan(problem(orders, fleet, RuleSet.empty(), PlanMode.FULL))
                .totalCost().krw();
        long fast = new SavingsClarkeWright()
                .plan(problem(orders, fleet, RuleSet.empty(), PlanMode.FAST)).totalCost().krw();

        assertThat(fast)
                .as("전제: 이 문제에서 개선 단계가 실제로 값을 만든다. 아니면 아래 비교는 "
                        + "'FAST 가 5단계를 끈다' 가 아니라 '5단계가 아무것도 안 한다' 로도 통과한다")
                .isGreaterThan(full);
        assertThat(new SavingsClarkeWright().name()).isEqualTo("savings-cw+ls");
    }
}
