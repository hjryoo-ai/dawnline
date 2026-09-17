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
import com.dawnline.dispatch.domain.optimizer.HaversineDistance;
import com.dawnline.dispatch.domain.optimizer.OrderId;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.PlanningBudget;
import com.dawnline.dispatch.domain.optimizer.PlanningDeadline;
import com.dawnline.dispatch.domain.optimizer.PlanningProblem;
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
 * savings 구성 단계 ([ADR-042]).
 *
 * <p>여기서 보는 것은 <strong>무엇을 잇고 무엇을 잇지 않는가</strong>다. 비용의 크기는 이 테스트의
 * 일이 아니라 {@code docs/benchmarks/phase4-savings-cw.md} 의 일이다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("SavingsMerger — 잇는 것은 거리가 정하고, 잇지 않는 것은 조합이 정한다")
class SavingsMergerTest {

    private static final Instant START = Instant.parse("2026-09-06T01:00:00Z");
    private static final GeoPoint CAMP = GeoPoint.of(37.5663, 126.9779);
    private static final UUID CAMP_ID = Ids.newId();
    private static final TimeWindow WINDOW = new TimeWindow(START, START.plus(Duration.ofHours(8)));

    /** 100 kg 짜리 일반 화물. 용량 축을 눈에 보이게 하려고 무겁게 잡는다. */
    private static final Parcel HEAVY = new Parcel(100_000, 1_000, false, false);

    private static final Parcel HEAVY_COLD = new Parcel(100_000, 1_000, true, false);

    private static final Parcel LIGHT = new Parcel(1_000, 10, false, false);

    private static final Parcel LIGHT_COLD = new Parcel(1_000, 10, true, false);

    /**
     * 캠프에서 북쪽 5 km 에 동서로 늘어선 지점. <strong>서로 가깝고 캠프에서 멀다</strong> —
     * savings 가 크게 나오는 배치이고, 그래서 제약이 없으면 전부 한 라우트가 된다.
     */
    private static Stop inLine(int index, Parcel parcel) {
        GeoPoint point = GeoPoint.of(CAMP.lat() + 0.045d, CAMP.lng() + 0.003d * index);
        return Stop.of(new Candidate(OrderId.of(Ids.newId()), point, parcel, WINDOW, 60, 0));
    }

    private static VehicleSpec vehicle(int maxWeightG, boolean cold) {
        return new VehicleSpec(VehicleId.of(Ids.newId()), new Capacity(maxWeightG, 100_000_000),
                new VehicleAttrs("VAN", cold, false),
                new TimeWindow(START, START.plus(Duration.ofHours(10))),
                VehicleCost.krw(45_000, 600, 250));
    }

    /** 이 테스트들이 보는 룰만 — 시간 축이 섞이면 무엇이 거절했는지 알 수 없다. */
    private static RuleSet rules(int maxStops) {
        List<RuleDefinition> definitions = new ArrayList<>(List.of(
                new RuleDefinition("cold-chain", RuleType.VEHICLE_ATTRIBUTE_MATCH,
                        RuleSeverity.HARD, 10,
                        Map.of("orderFlag", "requiresCold", "vehicleFlag", "isCold")),
                new RuleDefinition("capacity", RuleType.VEHICLE_CAPACITY, RuleSeverity.HARD, 15,
                        Map.of())));
        if (maxStops > 0) {
            definitions.add(new RuleDefinition("max-stops", RuleType.MAX_STOPS_PER_ROUTE,
                    RuleSeverity.HARD, 20, Map.of("max", maxStops)));
        }
        return DispatchRules.ruleSet(definitions, 1);
    }

    private static PlanningProblem problem(List<VehicleSpec> vehicles, RuleSet rules) {
        return new PlanningProblem(new WaveRef(Ids.newId(), CAMP_ID, "SAME_DAY", START),
                new CampDepot(CAMP_ID, CAMP), List.of(), vehicles, rules, new CostModel(),
                new HaversineDistance(1.3d, 25.0d),
                new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(3)), PlanMode.FULL,
                1.0d, START, 1L);
    }

    /** 이 테스트들에서 마감은 물지 않는다 — 스톱워치가 움직이지 않으므로 결정적이다. */
    private static PlanningDeadline noDeadline() {
        return new PlanningDeadline(() -> 0L, Duration.ofSeconds(30));
    }

    private static List<List<Stop>> merge(List<Stop> stops, List<VehicleSpec> fleet, RuleSet rules) {
        return SavingsMerger.merge(problem(fleet, rules), stops, noDeadline());
    }

    /** 냉장을 요구하는 라우트들의 stop 합 — 집계 불변식이 재는 값이다. */
    private static int coldSeatsUsed(List<List<Stop>> routes) {
        return routes.stream()
                .filter(route -> route.stream().anyMatch(stop -> stop.parcel().requiresCold()))
                .mapToInt(List::size)
                .sum();
    }

    @Test
    void 가깝고_캠프에서_먼_stop_들은_한_라우트가_된다() {
        List<Stop> stops = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            stops.add(inLine(i, LIGHT));
        }

        List<List<Stop>> routes = merge(stops, List.of(vehicle(10_000_000, false)), rules(0));

        assertThat(routes).singleElement(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(6);
    }

    @Test
    void 조합이_커지면_그_조합을_덮는_차량으로_잰다() {
        // (b) 의 핵심. 냉장 stop 하나가 붙는 순간 그 라우트 전체가 냉장차를 요구하므로,
        // 「가장 큰 차」가 아니라 「그 조합을 덮는 차 중 가장 큰 것」의 용량으로 재야 한다.
        // 큰 일반 밴은 600 kg 를 싣지만 냉장차는 250 kg 까지다.
        List<VehicleSpec> fleet = List.of(vehicle(1_000_000, false), vehicle(250_000, true));

        List<Stop> allPlain = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            allPlain.add(inLine(i, HEAVY));
        }
        assertThat(merge(allPlain, fleet, rules(0)))
                .as("전제: 같은 기하·같은 무게에서 조합이 없으면 여섯이 한 라우트가 된다. "
                        + "아래가 갈라지는 이유는 기하가 아니라 조합이다")
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(6);

        List<Stop> withCold = new ArrayList<>(allPlain);
        withCold.set(0, inLine(0, HEAVY_COLD));

        List<List<Stop>> routes = merge(withCold, fleet, rules(0));

        List<Stop> coldRoute = routes.stream()
                .filter(route -> route.stream().anyMatch(stop -> stop.parcel().requiresCold()))
                .findFirst().orElseThrow();
        assertThat(coldRoute)
                .as("냉장 stop 이 들어간 라우트는 냉장차 250 kg 안이어야 한다 — 일반 밴의 1 t 가 "
                        + "아니라")
                .hasSizeLessThanOrEqualTo(2);
        assertThat(routes).hasSizeGreaterThan(1);
    }

    @Test
    void 조합_좌석이_모자라면_병합하지_않는다() {
        // [ADR-039] 결정 2 를 구성 단계로 옮긴 것. 냉장차 한 대 × 상한 4 = 냉장 슬롯 4개이고,
        // 냉장을 요구하게 된 stop 의 합이 그 수를 넘는 병합은 거절한다. 거절이 없으면 냉장
        // 라우트 두 개가 각각 4 stop 까지 자라 여덟이 된다 — 그중 넷은 실을 차가 없다.
        List<Stop> stops = coldTwoAndSixPlain();
        List<VehicleSpec> fleet = List.of(vehicle(10_000_000, true), vehicle(10_000_000, false),
                vehicle(10_000_000, false), vehicle(10_000_000, false));

        List<List<Stop>> routes = merge(stops, fleet, rules(4));

        assertThat(coldSeatsUsed(routes)).isLessThanOrEqualTo(4);
    }

    @Test
    void 냉장차가_늘면_그만큼_더_잇는다() {
        // 앞 테스트의 짝. 거절의 근거가 「냉장이라서」가 아니라 「그 조합의 슬롯이 모자라서」
        // 라는 것을 이 대조가 말한다 — 슬롯이 8 이 되면 같은 stop 들이 더 이어진다.
        List<Stop> stops = coldTwoAndSixPlain();
        List<VehicleSpec> fleet = List.of(vehicle(10_000_000, true), vehicle(10_000_000, true),
                vehicle(10_000_000, false), vehicle(10_000_000, false));

        List<List<Stop>> routes = merge(stops, fleet, rules(4));

        assertThat(coldSeatsUsed(routes)).isGreaterThan(4);
    }

    @Test
    void 상한을_말하는_룰이_없으면_그_불변식도_없다() {
        // 제외한 것이 왜 제외인지를 검사한다 (CLAUDE.md 「집합을 도는 검사」). 슬롯의 단위는
        // stop 상한이고, 상한을 말하는 룰이 없으면 셀 자리가 없다 — 그때 이 축은 아예 없다
        // ([ADR-039] 결정 2 와 같은 이유). 「잊었다」가 아니라 「검토하고 뺐다」이다.
        List<Stop> stops = coldTwoAndSixPlain();
        List<VehicleSpec> fleet = List.of(vehicle(10_000_000, true), vehicle(10_000_000, false),
                vehicle(10_000_000, false), vehicle(10_000_000, false));

        List<List<Stop>> routes = merge(stops, fleet, rules(0));

        assertThat(coldSeatsUsed(routes))
                .as("상한이 없으면 슬롯도 없다 — 냉장차 한 대뿐이어도 집계가 막지 않는다")
                .isGreaterThan(4);
    }

    @Test
    void 그_조합을_실을_차가_없으면_잇지_않는다() {
        // 냉장차가 아예 없으면 냉장 stop 은 혼자 남는다. 이어 붙이면 「아무 차도 못 싣는
        // 라우트」를 만들고, 그 라우트에 붙은 일반 stop 까지 함께 미배정이 된다 —
        // [ADR-033] 이 통합 키에서 막은 「희소한 능력 하나가 인질을 잡는다」와 같은 일이다.
        List<Stop> stops = new ArrayList<>();
        stops.add(inLine(0, LIGHT_COLD));
        for (int i = 1; i < 6; i++) {
            stops.add(inLine(i, LIGHT));
        }

        List<List<Stop>> routes = merge(stops, List.of(vehicle(10_000_000, false)), rules(0));

        assertThat(routes).hasSize(2);
        assertThat(routes.stream()
                .filter(route -> route.stream().anyMatch(stop -> stop.parcel().requiresCold()))
                .findFirst().orElseThrow())
                .hasSize(1);
    }

    @Test
    void 같은_입력이면_같은_라우트가_나온다() {
        List<Stop> stops = coldTwoAndSixPlain();
        List<VehicleSpec> fleet = List.of(vehicle(10_000_000, true), vehicle(10_000_000, false));

        assertThat(merge(stops, fleet, rules(4))).isEqualTo(merge(stops, fleet, rules(4)));
    }

    /** 냉장 둘 + 일반 여섯. 냉장 stop 을 양 끝이 아니라 사이에 둬 병합이 실제로 일어나게 한다. */
    private static List<Stop> coldTwoAndSixPlain() {
        List<Stop> stops = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            stops.add(inLine(i, i == 1 || i == 5 ? LIGHT_COLD : LIGHT));
        }
        return stops;
    }
}
