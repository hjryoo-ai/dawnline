package com.dawnline.dispatch.domain.optimizer.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
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
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SweepGreedyNearestNeighborTest {

    private static final Instant START = Instant.parse("2026-09-06T01:00:00Z");
    private static final GeoPoint CAMP = GeoPoint.of(37.5663, 126.9779);
    private static final UUID CAMP_ID = Ids.newId();
    private static final TimeWindow WINDOW = new TimeWindow(START, START.plus(Duration.ofHours(8)));

    private final SweepGreedyNearestNeighbor strategy = new SweepGreedyNearestNeighbor();

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
        return new PlanningProblem(new WaveRef(Ids.newId(), CAMP_ID, "SAME_DAY", START),
                new CampDepot(CAMP_ID, CAMP), candidates, vehicles, rules, new CostModel(),
                new HaversineDistance(1.3d, 25.0d),
                new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(3)), START, 1L);
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
        assertThat(strategy.name()).isEqualTo("sweep-greedy-nn");
    }

    @Test
    void 한_차가_다_실을_수_있으면_한_라우트로_간다() {
        // 클러스터 수는 총수요/용량에서 나온다. 차가 넉넉하다고 나눠 싣지 않는다 — 그러면
        // 굴리지 않아도 될 차의 고정비를 문다(측정: 고정비가 총비용 격차의 31~96%였다).
        PlanningProblem problem = problem(fourSectors(5),
                List.of(vehicle("VAN", 10_000_000, false), vehicle("VAN", 10_000_000, false),
                        vehicle("VAN", 10_000_000, false), vehicle("VAN", 10_000_000, false)),
                RuleSet.empty());

        PlanResult result = strategy.plan(problem);

        assertThat(result.routes()).as("한 대가 20 stop 을 다 감당한다").hasSize(1);
        assertThat(result.unassigned()).isEmpty();
    }

    @Test
    void 용량이_모자라면_부챗살마다_다른_차가_간다() {
        // 20 stop × 100 kg = 2 t, 차 한 대가 500 kg → 네 클러스터 → 네 라우트.
        List<Candidate> orders = new ArrayList<>();
        for (double angle : new double[] {0, 90, 180, 270}) {
            for (int i = 0; i < 5; i++) {
                orders.add(at(angle + i * 0.5d, 1.0d + i * 0.1d,
                        new Parcel(100_000, 1, false, false)));
            }
        }
        PlanningProblem problem = problem(orders,
                List.of(vehicle("VAN", 500_000, false), vehicle("VAN", 500_000, false),
                        vehicle("VAN", 500_000, false), vehicle("VAN", 500_000, false)),
                DispatchRules.ruleSet(List.of(new RuleDefinition("capacity",
                        RuleType.VEHICLE_CAPACITY, RuleSeverity.HARD, 15, Map.of())), 1));

        PlanResult result = strategy.plan(problem);

        assertThat(result.routes()).hasSize(4);
        assertThat(result.unassigned()).isEmpty();
    }

    @Test
    void 모든_주문이_배정되거나_사유와_함께_남는다() {
        PlanningProblem problem = problem(fourSectors(4),
                List.of(vehicle("VAN", 10_000_000, false)), RuleSet.empty());

        PlanResult result = strategy.plan(problem);

        int assigned = result.routes().stream()
                .mapToInt(com.dawnline.dispatch.domain.optimizer.PlannedRoute::orderCount).sum();
        assertThat(assigned + result.unassigned().size()).isEqualTo(16);
        assertThat(result.unassigned())
                .allSatisfy(unassigned -> assertThat(unassigned.reason()).isNotBlank());
    }

    @Test
    void 냉장_주문은_냉장차로_가고_사유가_남는다() {
        // 미배정 사유가 "실을 차가 없다" 로만 남으면 §6.3 이 설명을 요구한 이유가 사라진다.
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
    void 같은_입력이면_같은_결과다() {
        PlanningProblem problem = problem(fourSectors(4),
                List.of(vehicle("VAN", 10_000_000, false), vehicle("VAN", 10_000_000, false)),
                RuleSet.empty());

        assertThat(strategy.plan(problem).totalCost())
                .isEqualTo(new SweepGreedyNearestNeighbor().plan(problem).totalCost());
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
}
