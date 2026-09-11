package com.dawnline.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.domain.PlanMode;
import com.dawnline.dispatch.domain.optimizer.CampDepot;
import com.dawnline.dispatch.domain.optimizer.Candidate;
import com.dawnline.dispatch.domain.optimizer.Capacity;
import com.dawnline.dispatch.domain.optimizer.CostModel;
import com.dawnline.dispatch.domain.optimizer.HaversineDistance;
import com.dawnline.dispatch.domain.optimizer.OrderId;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.PlanningBudget;
import com.dawnline.dispatch.domain.optimizer.PlanningProblem;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.StopMerger;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 완화 문제의 고정비 하한 — <strong>이보다 적은 고정비는 불가능하다</strong> (§6.9, [ADR-038]).
 *
 * <p>손으로 검산되는 함대로 먼저 보고(하한이 «하한» 인지), 그다음 실제 데이터셋에서 성질만 본다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("고정비 하한 — 불가능의 경계")
class FixedCostFloorTest {

    private static final Instant START = Instant.parse("2026-09-06T01:00:00Z");
    private static final GeoPoint CAMP = GeoPoint.of(37.5663, 126.9779);
    private static final UUID CAMP_ID = Ids.newId();
    private static final TimeWindow WINDOW = new TimeWindow(START, START.plus(Duration.ofHours(8)));
    private static final PlanningBudget BUDGET =
            new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(3));

    @Test
    void stop_축은_단위_슬롯당_고정비가_싼_차부터_분수로_덮는다() {
        // stop 상한 10. 밴 10,000원(=1,000원/슬롯) 두 대, 트럭 30,000원(=3,000원/슬롯) 한 대.
        // stop 25 개 → 밴 둘(20 슬롯, 20,000원) + 트럭 0.5 대(15,000원) = 35,000원. 손으로 검산된다.
        PlanningProblem problem = problem(maxStops(10), stops(25, light()),
                List.of(van(10_000), van(10_000), truck(30_000)));

        FixedCostFloor floor = FixedCostFloor.of(problem);

        assertThat(floor.feasible()).isTrue();
        assertThat(floor.krw()).isEqualTo(35_000L);
        assertThat(floor.bindingAxis()).isEqualTo("전체 stop");
    }

    @Test
    void 덮지_못하는_축이_있으면_실현_불가라고_말한다() {
        // 슬롯이 10 × 2 = 20 인데 stop 이 25 다. 어떤 함대도 다 싣지 못한다.
        PlanningProblem problem = problem(maxStops(10), stops(25, light()),
                List.of(van(10_000), van(10_000)));

        FixedCostFloor floor = FixedCostFloor.of(problem);

        assertThat(floor.feasible()).isFalse();
        assertThat(floor.uncoverable()).contains("전체 stop");
    }

    @Test
    void 냉장_축이_더_비싸면_그것이_무는_축이_된다() {
        // 전체 stop 20 개는 밴 둘(슬롯 10씩)로 20,000원이면 덮인다. 그런데 그중 15 개가 냉장이고
        // 냉장차는 트럭(30,000원/10슬롯) 둘뿐이다 → 냉장 stop 축이 30,000 + 15,000 = 45,000원.
        List<Candidate> demand = new ArrayList<>(stops(15, cold()));
        demand.addAll(stops(5, light()));
        PlanningProblem problem = problem(maxStops(10), demand,
                List.of(van(10_000), van(10_000), coldTruck(30_000), coldTruck(30_000)));

        FixedCostFloor floor = FixedCostFloor.of(problem);

        assertThat(floor.krw()).isEqualTo(45_000L);
        assertThat(floor.bindingAxis()).isEqualTo("냉장 stop");
    }

    @Test
    void 룰이_stop_상한을_말하지_않으면_적재_축만_본다() {
        // 상한이 없으면 stop 축은 잴 수 없다 — 「무한 슬롯」으로 접으면 0 원이 하한이 되어
        // 아무 말도 하지 않는 열이 된다. 중량 축은 그대로 답한다(25 × 1,000 g / 100,000 g).
        PlanningProblem problem = problem(RuleSet.empty(), stops(25, light()),
                List.of(van(10_000), van(10_000)));

        FixedCostFloor floor = FixedCostFloor.of(problem);

        assertThat(floor.bindingAxis()).isEqualTo("전체 중량");
        assertThat(floor.krw()).isEqualTo(2_500L);
    }

    @ParameterizedTest
    @EnumSource(value = Dataset.class, mode = EnumSource.Mode.EXCLUDE, names = "OVERLOAD")
    void 실현_가능한_데이터셋의_하한은_전_차량_고정비보다_싸다(Dataset dataset) {
        PlanningProblem problem = dataset(dataset);
        long wholeFleet = problem.vehicles().stream()
                .mapToLong(vehicle -> vehicle.cost().fixed().krw()).sum();

        FixedCostFloor floor = FixedCostFloor.of(problem);

        assertThat(floor.feasible())
                .as("%s — 실현 가능성 기준을 통과한 데이터셋은 총량으로도 덮을 수 있어야 한다",
                        dataset.cliName())
                .isTrue();
        assertThat(floor.krw())
                .as("%s 하한 %,d / 전 차량 %,d", dataset.cliName(), floor.krw(), wholeFleet)
                .isPositive()
                .isLessThan(wholeFleet);
    }

    /**
     * {@code overload} 가 위 목록에서 빠진 이유를 <strong>테스트가 스스로 말한다</strong>
     * (CLAUDE.md — 제외한 것이 왜 제외인지를 검사한다).
     */
    @Test
    void overload_는_stop_축을_덮지_못한다() {
        PlanningProblem problem = dataset(Dataset.OVERLOAD);
        List<Stop> stops = StopMerger.merge(problem.candidates());
        int slots = problem.vehicles().size() * 120;

        assertThat(stops.size())
                .as("전제: 이 데이터셋의 존재 이유가 «다 못 싣는다» 다 (stop %,d / 슬롯 %,d)",
                        stops.size(), slots)
                .isGreaterThan(slots);

        FixedCostFloor floor = FixedCostFloor.of(problem);

        assertThat(floor.feasible()).isFalse();
        assertThat(floor.uncoverable()).contains("전체 stop");
    }

    // ------------------------------------------------------------------ 픽스처

    private static PlanningProblem dataset(Dataset dataset) {
        return new DatasetGenerator(dataset, 20_260_905L, START)
                .generate(RuleSeed.load(RuleSeed.locate(), 1), BUDGET, PlanMode.FULL, 1.0d);
    }

    private static RuleSet maxStops(int max) {
        return DispatchRules.ruleSet(List.of(
                new RuleDefinition("max-stops", RuleType.MAX_STOPS_PER_ROUTE, RuleSeverity.HARD, 20,
                        Map.of("max", max))), 1);
    }

    private static Parcel light() {
        return new Parcel(1_000, 1_000, false, false);
    }

    private static Parcel cold() {
        return new Parcel(1_000, 1_000, true, false);
    }

    /** 좌표를 전부 다르게 둔다 — 통합(§6.5 1단계)이 stop 수를 줄이면 검산이 깨진다. */
    private static List<Candidate> stops(int count, Parcel parcel) {
        List<Candidate> candidates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            candidates.add(new Candidate(OrderId.of(Ids.newId()),
                    GeoPoint.of(CAMP.lat() + 0.01d * i, CAMP.lng() + 0.01d * i),
                    parcel, WINDOW, 60, 0));
        }
        return candidates;
    }

    private static VehicleSpec van(long fixedKrw) {
        return vehicle(fixedKrw, new Capacity(100_000, 100_000), new VehicleAttrs("VAN", false, false));
    }

    private static VehicleSpec truck(long fixedKrw) {
        return vehicle(fixedKrw, new Capacity(900_000, 900_000),
                new VehicleAttrs("TRUCK", false, false));
    }

    private static VehicleSpec coldTruck(long fixedKrw) {
        return vehicle(fixedKrw, new Capacity(900_000, 900_000),
                new VehicleAttrs("TRUCK", true, false));
    }

    private static VehicleSpec vehicle(long fixedKrw, Capacity capacity, VehicleAttrs attrs) {
        return new VehicleSpec(VehicleId.of(Ids.newId()), capacity, attrs,
                new TimeWindow(START, START.plus(Duration.ofHours(10))),
                VehicleCost.krw(fixedKrw, 600, 250));
    }

    private static PlanningProblem problem(RuleSet rules, List<Candidate> candidates,
            List<VehicleSpec> vehicles) {
        return new PlanningProblem(new WaveRef(Ids.newId(), CAMP_ID, "SAME_DAY", START),
                new CampDepot(CAMP_ID, CAMP), candidates, vehicles, rules,
                new CostModel(), new HaversineDistance(1.3d, 25.0d), BUDGET,
                PlanMode.FULL, 1.0d, START, 1L);
    }
}
