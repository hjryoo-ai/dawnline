package com.dawnline.dispatch.domain.optimizer.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.domain.optimizer.CampDepot;
import com.dawnline.dispatch.domain.optimizer.Candidate;
import com.dawnline.dispatch.domain.optimizer.Capacity;
import com.dawnline.dispatch.domain.optimizer.CostModel;
import com.dawnline.dispatch.domain.optimizer.HaversineDistance;
import com.dawnline.dispatch.domain.optimizer.OrderId;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.PlanningBudget;
import com.dawnline.dispatch.domain.optimizer.PlanningProblem;
import com.dawnline.dispatch.domain.optimizer.RouteAccumulator;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import com.dawnline.dispatch.domain.optimizer.Stop;
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
 * 미배정 정책 — 누가 빠지고 누가 다시 들어오는가 (ADR-028).
 *
 * <h2>이 테스트가 없던 동안 무슨 일이 있었나</h2>
 * §6.5 3단계는 "그래도 없으면 미배정" 이라고만 했고 <em>누구를</em> 남길지는 말하지 않았다.
 * 그래서 <strong>마지막 클러스터에 남은 주문</strong>이 그대로 떨어졌다 — 규칙이 아니라 순서였다.
 * {@code small} 에서 두 전략의 미배정 건수가 9 로 같은데 페널티가 20,000원 달랐던 것이 그 흔적이다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("UnassignedRepair — 미배정 정책")
class UnassignedRepairTest {

    private static final Instant START = Instant.parse("2026-09-06T01:00:00Z");
    private static final GeoPoint CAMP = GeoPoint.of(37.5663, 126.9779);
    private static final UUID CAMP_ID = Ids.newId();
    private static final TimeWindow WINDOW = new TimeWindow(START, START.plus(Duration.ofHours(8)));

    @Test
    void 자리가_모자라면_페널티가_싼_것부터_남는다() {
        // 자리는 둘, 후보는 넷. 목적함수를 그대로 따르면 비싼 둘이 실리고 싼 둘이 남는다.
        PlanningProblem problem = problem(twoStopLimit(), List.of(vehicle(false)));
        List<Stop> waiting = List.of(east(1, 0), east(2, 3), east(3, 1), east(4, 2));

        UnassignedRepair.Outcome outcome =
                UnassignedRepair.repair(problem, List.of(empty(problem, vehicle(false))), waiting);

        assertThat(outcome.inserted()).as("전제: 두 자리는 실제로 채워져야 한다").isEqualTo(2);
        assertThat(outcome.unassigned()).extracting(Stop::priority)
                .as("남는 것은 우선도가 낮은 쪽이다 — 우연이 아니라 규칙이 정한다")
                .containsExactlyInAnyOrder(0, 1);
    }

    @Test
    void 실을_수_있어도_페널티보다_비싸면_싣지_않는다() {
        // §6.1 목적함수 그대로다. 페널티 1원짜리 주문을 위해 30 km 를 더 달리는 계획은
        // <strong>더 나쁜 계획</strong>이고, 그 판단이 이 한 줄에 들어 있다.
        PlanningProblem problem = problem(cheapUnassigned(), List.of(vehicle(false)));

        UnassignedRepair.Outcome outcome = UnassignedRepair.repair(problem,
                List.of(empty(problem, vehicle(false))), List.of(east(30, 0)));

        assertThat(outcome.inserted()).isZero();
        assertThat(outcome.unassigned()).hasSize(1);
    }

    @Test
    void 하드_룰을_어기는_자리에는_넣지_않는다() {
        // 아껴 두는 것이 아니라 <strong>실을 수 없는</strong> 것이다. 재삽입이 여기서 무너지면
        // 개선 단계의 방어선(§6.5 6단계)이 잡기 전에 냉장이 상온차에 실린다.
        PlanningProblem problem = problem(coldChain(), List.of(vehicle(false)));
        Stop cold = new Stop(GeoPoint.of(CAMP.lat(), CAMP.lng() + 0.01d),
                List.of(OrderId.of(Ids.newId())), new Parcel(1_000, 2_000, true, false),
                WINDOW, 60, 0);

        UnassignedRepair.Outcome outcome =
                UnassignedRepair.repair(problem, List.of(empty(problem, vehicle(false))), List.of(cold));

        assertThat(outcome.inserted()).isZero();
        assertThat(outcome.unassigned()).containsExactly(cold);
    }

    @Test
    void 냉장차가_있으면_싣는다() {
        // 위 테스트가 "룰이 아니라 재삽입이 고장나서" 통과하는 것이 아님을 보인다.
        PlanningProblem problem = problem(coldChain(), List.of(vehicle(true)));
        Stop cold = new Stop(GeoPoint.of(CAMP.lat(), CAMP.lng() + 0.01d),
                List.of(OrderId.of(Ids.newId())), new Parcel(1_000, 2_000, true, false),
                WINDOW, 60, 0);

        assertThat(UnassignedRepair.repair(problem, List.of(empty(problem, vehicle(true))),
                List.of(cold)).inserted()).isEqualTo(1);
    }

    @Test
    void 같은_입력이면_같은_결과다() {
        PlanningProblem problem = problem(twoStopLimit(), List.of(vehicle(false)));
        List<Stop> waiting = List.of(east(1, 2), east(2, 2), east(3, 2), east(4, 2));

        List<Integer> once = insertedOrder(problem, waiting);
        List<Integer> twice = insertedOrder(problem, waiting);

        assertThat(twice).as("우선도까지 동률이면 주문 id 가 정한다 (불변규칙 12)").isEqualTo(once);
    }

    // ------------------------------------------------------------------ 재료

    private static List<Integer> insertedOrder(PlanningProblem problem, List<Stop> waiting) {
        UnassignedRepair.Outcome outcome =
                UnassignedRepair.repair(problem, List.of(empty(problem, vehicle(false))), waiting);
        return outcome.routes().getFirst().state().stops().stream()
                .map(planned -> (int) Math.round(
                        (planned.stop().point().lng() - CAMP.lng()) * 100))
                .toList();
    }

    private static RouteAccumulator empty(PlanningProblem problem, VehicleSpec vehicle) {
        return new RouteAccumulator(problem.rules(), vehicle, problem.depot(), problem.distance(),
                problem.startedAt());
    }

    /** 캠프에서 동쪽으로 {@code steps} 칸(약 0.9 km). */
    private static Stop east(int steps, int priority) {
        return new Stop(GeoPoint.of(CAMP.lat(), CAMP.lng() + 0.01d * steps),
                List.of(OrderId.of(Ids.newId())), new Parcel(1_000, 2_000, false, false),
                WINDOW, 60, priority);
    }

    private static VehicleSpec vehicle(boolean cold) {
        return new VehicleSpec(VehicleId.of(Ids.newId()), new Capacity(400_000, 1_200_000),
                new VehicleAttrs("VAN", cold, false),
                new TimeWindow(START, START.plus(Duration.ofHours(10))),
                VehicleCost.krw(45_000, 600, 250));
    }

    /** 자리를 둘로 묶고, 우선도가 값에 실제로 들어가는 미배정 페널티. */
    private static RuleSet twoStopLimit() {
        return DispatchRules.ruleSet(List.of(
                new RuleDefinition("max-stops", RuleType.MAX_STOPS_PER_ROUTE, RuleSeverity.HARD, 10,
                        Map.of("max", 2)),
                new RuleDefinition("unassigned", RuleType.UNASSIGNED_PENALTY, RuleSeverity.SOFT, 20,
                        Map.of("baseKrw", 50_000, "perPriorityKrw", 30_000))), 1);
    }

    /** 페널티가 아주 싼 룰셋 — 실을 수 있어도 실을 값어치가 없다. */
    private static RuleSet cheapUnassigned() {
        return DispatchRules.ruleSet(List.of(
                new RuleDefinition("unassigned", RuleType.UNASSIGNED_PENALTY, RuleSeverity.SOFT, 20,
                        Map.of("baseKrw", 1, "perPriorityKrw", 0))), 1);
    }

    private static RuleSet coldChain() {
        return DispatchRules.ruleSet(List.of(
                new RuleDefinition("cold-chain", RuleType.VEHICLE_ATTRIBUTE_MATCH,
                        RuleSeverity.HARD, 10,
                        Map.of("orderFlag", "requiresCold", "vehicleFlag", "isCold")),
                new RuleDefinition("unassigned", RuleType.UNASSIGNED_PENALTY, RuleSeverity.SOFT, 20,
                        Map.of("baseKrw", 500_000, "perPriorityKrw", 0))), 1);
    }

    private static PlanningProblem problem(RuleSet rules, List<VehicleSpec> vehicles) {
        return new PlanningProblem(new WaveRef(Ids.newId(), CAMP_ID, "SAME_DAY", START),
                new CampDepot(CAMP_ID, CAMP), List.<Candidate>of(), vehicles, rules,
                new CostModel(), new HaversineDistance(1.3d, 25.0d),
                new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(3)), START, 1L);
    }
}
