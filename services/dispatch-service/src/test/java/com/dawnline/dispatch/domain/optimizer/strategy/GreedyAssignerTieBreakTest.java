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
import com.dawnline.dispatch.domain.optimizer.PlanResult;
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
 * 한계비용이 같을 때 어느 차를 고르는가 (§6.5 3단계 동률 규칙, ADR-031).
 *
 * <h2>이 테스트가 없던 동안 무슨 일이 있었나</h2>
 * 동률은 {@code GreedyAssigner} 의 {@code trials} 순회 순서 — 즉 어댑터의 {@code ORDER BY code}
 * — 로 깨지고 있었다. 시드에서 캠프마다 첫 차량이 냉장이라, 작은 웨이브를 한 대가 통째로
 * 흡수하는 상황에서는 <strong>언제나 냉장 차량이 뽑혔다</strong>. 그래서 {@code make demo} 의
 * cold-chain 공허성 검사("비냉장 차량이 한 대도 쓰이지 않았으면 '냉장 차량에만' 은 자동으로
 * 참이다")가 실패했고, 그것이 시각과 시드 배분에 따라 통과·실패를 갈랐다.
 *
 * <h2>순서 운을 없애는 방법</h2>
 * 차량 목록을 <strong>양쪽 순서로 모두</strong> 넣어 본다. 한 순서만 보면 이 테스트 자체가
 * 순서 운으로 통과할 수 있다 — 고치려는 결함과 같은 형태다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("GreedyAssignerTieBreak — 능력이 적은 차를 먼저")
class GreedyAssignerTieBreakTest {

    private static final Instant START = Instant.parse("2026-09-06T01:00:00Z");
    private static final GeoPoint CAMP = GeoPoint.of(37.5663, 126.9779);
    private static final UUID CAMP_ID = Ids.newId();
    private static final TimeWindow WINDOW = new TimeWindow(START, START.plus(Duration.ofHours(8)));

    private final SweepGreedyNearestNeighbor strategy = new SweepGreedyNearestNeighbor();

    @Test
    void 비용이_같으면_상온_주문은_비냉장_차량이_싣는다() {
        VehicleSpec cold = vehicle(true, false);
        VehicleSpec warm = vehicle(false, false);

        for (List<VehicleSpec> fleet : List.of(List.of(cold, warm), List.of(warm, cold))) {
            PlanResult result = strategy.plan(problem(warmOrders(), fleet));

            assertThat(result.routes()).as("한 대면 충분한 문제다").hasSize(1);
            assertThat(result.routes().getFirst().vehicle())
                    .as("차량 목록 순서 %s — 동률이 순서로 깨지면 이 값이 순서를 따라 흔들린다",
                            fleet.stream().map(spec -> spec.attrs().cold() ? "냉장" : "상온").toList())
                    .isEqualTo(warm.id());
        }
    }

    @Test
    void 위험물_허용은_능력_순위에_넣지_않는다() {
        // 같은 논리로 넣었다가 **측정이 반대여서** 뺐다: large 에서 위험물 미배정이
        // 99 → 115 로 늘고 총비용이 3.9% 올랐다(phase3-baseline §4-7). 아껴 두기는 아낀
        // 자원이 나중에 그 수요와 짝지어질 때만 이득인데, 위험물은 2% 라 그 짝짓기가
        // 일어나지 않는다. 이 경계를 테스트로 고정해 둔다 — 다시 넣으려면 다시 재야 한다.
        assertThat(vehicle(false, true).capabilityRank())
                .as("위험물 허용은 순위를 올리지 않는다")
                .isEqualTo(vehicle(false, false).capabilityRank());
        assertThat(vehicle(true, false).capabilityRank())
                .as("냉장은 올린다")
                .isGreaterThan(vehicle(false, false).capabilityRank());
        assertThat(vehicle(true, true).capabilityRank())
                .as("냉장+위험물은 냉장과 같다")
                .isEqualTo(vehicle(true, false).capabilityRank());
    }

    @Test
    void 능력이_같으면_작은_차를_먼저_쓴다() {
        VehicleSpec big = vehicle(false, false, 10_000_000);
        VehicleSpec small = vehicle(false, false, 5_000_000);

        for (List<VehicleSpec> fleet : List.of(List.of(big, small), List.of(small, big))) {
            PlanResult result = strategy.plan(problem(warmOrders(), fleet));

            assertThat(result.routes().getFirst().vehicle())
                    .as("소형이 실을 수 있으면 대형을 꺼내지 않는다")
                    .isEqualTo(small.id());
        }
    }

    @Test
    void 냉장_주문은_그래도_냉장_차량이_싣는다() {
        // 동률 규칙이 하드 룰을 이기면 안 된다 — 아껴 두는 것이지 쓰지 않는 것이 아니다.
        VehicleSpec cold = vehicle(true, false);
        VehicleSpec warm = vehicle(false, false);
        List<Candidate> orders = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            orders.add(at(i * 90.0d, 1.0d, new Parcel(1_000, 1_000, true, false)));
        }

        RuleSet coldChain = DispatchRules.ruleSet(List.of(new RuleDefinition("cold-chain",
                RuleType.VEHICLE_ATTRIBUTE_MATCH, RuleSeverity.HARD, 10,
                Map.of("orderFlag", "requiresCold", "vehicleFlag", "isCold"))), 1);

        PlanResult result = strategy.plan(problem(orders, List.of(warm, cold), coldChain));

        assertThat(result.routes()).hasSize(1);
        assertThat(result.routes().getFirst().vehicle()).isEqualTo(cold.id());
    }

    /** 능력이 같은 두 대는 id 로 갈린다 — 재현성 때문이다 (불변규칙 12). */
    @Test
    void 능력과_용량이_같으면_id_로_갈려_재현된다() {
        VehicleSpec first = vehicle(false, false);
        VehicleSpec second = vehicle(false, false);
        VehicleId expected = VehicleSpec.LEAST_CAPABLE_FIRST.compare(first, second) <= 0
                ? first.id() : second.id();

        for (List<VehicleSpec> fleet : List.of(List.of(first, second), List.of(second, first))) {
            assertThat(strategy.plan(problem(warmOrders(), fleet)).routes().getFirst().vehicle())
                    .isEqualTo(expected);
        }
    }

    private static List<Candidate> warmOrders() {
        List<Candidate> orders = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            orders.add(at(i * 90.0d, 1.0d, Parcel.EMPTY));
        }
        return orders;
    }

    private static Candidate at(double degrees, double km, Parcel parcel) {
        double radians = Math.toRadians(degrees);
        GeoPoint point = GeoPoint.of(CAMP.lat() + 0.009d * km * Math.cos(radians),
                CAMP.lng() + 0.009d * km * Math.sin(radians));
        return new Candidate(OrderId.of(Ids.newId()), point, parcel, WINDOW, 60, 0);
    }

    /** 비용·용량·근무창이 <strong>완전히 같은</strong> 두 대. 다른 것은 능력뿐이다. */
    private static VehicleSpec vehicle(boolean cold, boolean hazmat) {
        return vehicle(cold, hazmat, 10_000_000);
    }

    private static VehicleSpec vehicle(boolean cold, boolean hazmat, int maxWeightG) {
        return new VehicleSpec(VehicleId.of(Ids.newId()), new Capacity(maxWeightG, 100_000_000),
                new VehicleAttrs("VAN", cold, hazmat),
                new TimeWindow(START, START.plus(Duration.ofHours(10))),
                VehicleCost.krw(45_000, 600, 250));
    }

    private static PlanningProblem problem(List<Candidate> candidates, List<VehicleSpec> fleet) {
        return problem(candidates, fleet, RuleSet.empty());
    }

    private static PlanningProblem problem(List<Candidate> candidates, List<VehicleSpec> fleet,
            RuleSet rules) {
        return new PlanningProblem(new WaveRef(Ids.newId(), CAMP_ID, "SAME_DAY", START),
                new CampDepot(CAMP_ID, CAMP), candidates, fleet, rules, new CostModel(),
                new HaversineDistance(1.3d, 25.0d),
                new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(3)), START, 1L);
    }
}
