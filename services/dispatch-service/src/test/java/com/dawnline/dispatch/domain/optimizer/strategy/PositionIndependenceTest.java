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
 * 재삽입 가지치기가 기대는 <strong>정리</strong>를 검사한다 ([ADR-037]).
 *
 * <blockquote>
 * {@code positionIndependent()} 인 하드 룰의 판정은 stop 을 라우트의 <strong>어느 자리에</strong>
 * 넣든 같다.
 * </blockquote>
 *
 * <h2>왜 이 테스트가 가지치기보다 중요한가</h2>
 * 가지치기는 «시도해도 못 들어가는 자리를 시도하지 않는 것» 이라 결과를 바꿀 수 없다 — 정리가
 * 참인 <em>동안</em>은. 정리가 깨지면(예: 누군가 순서를 보는 룰에 표시를 단다) 가지치기는
 * <strong>들어갈 수 있었던 자리를 건너뛰고</strong>, 그 차이는 테스트가 아니라 벤치마크 수치에만
 * 조용히 나타난다. 그래서 여기서 보는 것은 «빨라졌는가» 가 아니라 <strong>«정리가 아직 참인가»</strong> 다.
 *
 * <p>검사 방식은 정의 그대로다 — 라우트의 <em>모든</em> 자리에 실제로 끼워 넣어 보고, 그 룰만
 * 켠 채 라우트를 다시 만들어 실행 가능한지 본다. 그 결과가 자리마다 같아야 하고,
 * {@code checkPositionIndependent} 가 그것을 미리 맞혀야 한다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("위치 무관 하드 룰 — 재삽입 가지치기의 전제")
class PositionIndependenceTest {

    private static final Instant START = Instant.parse("2026-09-06T01:00:00Z");
    private static final GeoPoint CAMP = GeoPoint.of(37.5663, 126.9779);
    private static final UUID CAMP_ID = Ids.newId();
    private static final TimeWindow WINDOW = new TimeWindow(START, START.plus(Duration.ofHours(8)));

    @Test
    void stop_상한은_어느_자리에_넣어도_같은_답을_낸다() {
        // 상한 3, 이미 3개 — 어디에 끼워도 4개가 된다.
        RuleSet rules = maxStops(3);
        assertVerdictHoldsAtEveryPosition(rules, stops(3), stop(9), false);
    }

    @Test
    void 자리가_남으면_상한은_어느_자리에서도_막지_않는다() {
        // 위 테스트가 "룰이 언제나 거절해서" 통과하는 것이 아님을 보인다.
        assertVerdictHoldsAtEveryPosition(maxStops(5), stops(3), stop(9), true);
    }

    @Test
    void 용량은_어느_자리에_넣어도_같은_답을_낸다() {
        // 덧셈은 교환법칙을 따른다 — 순서가 바뀌어도 누적 적재의 최종값은 같다.
        RuleSet rules = capacity();
        List<Stop> route = List.of(heavy(1), heavy(2), heavy(3));

        assertVerdictHoldsAtEveryPosition(rules, route, heavy(9), false);
        assertVerdictHoldsAtEveryPosition(rules, route, light(9), true);
    }

    @Test
    void 차량_속성은_어느_자리에_넣어도_같은_답을_낸다() {
        RuleSet rules = coldChain();

        assertVerdictHoldsAtEveryPosition(rules, stops(3), cold(9), false);
        assertVerdictHoldsAtEveryPosition(rules, stops(3), stop(9), true);
    }

    /**
     * 정리 그대로 — 모든 자리에 실제로 넣어 보고, 미리 낸 판정이 자리마다 맞는지 본다.
     *
     * @param rules    그 룰 하나만 켠 룰셋
     * @param route    기존 라우트
     * @param stop     끼우려는 stop
     * @param expected 들어갈 수 있어야 하는가
     */
    private static void assertVerdictHoldsAtEveryPosition(RuleSet rules, List<Stop> route,
            Stop stop, boolean expected) {

        PlanningProblem problem = problem(rules);
        VehicleSpec vehicle = vehicle();
        RouteAccumulator seeded = RouteRebuild.accumulate(problem, vehicle, route);
        assertThat(seeded).as("전제: 기존 라우트 자체는 룰을 통과해야 한다").isNotNull();

        assertThat(rules.checkPositionIndependent(stop, vehicle, seeded.state()).feasible())
                .as("미리 낸 판정")
                .isEqualTo(expected);

        for (int at = 0; at <= route.size(); at++) {
            List<Stop> candidate = new ArrayList<>(route);
            candidate.add(at, stop);

            assertThat(RouteRebuild.accumulate(problem, vehicle, candidate) != null)
                    .as("%d번째 자리 — 위치 무관 룰의 판정이 자리에 따라 달라지면 재삽입 "
                            + "가지치기가 들어갈 수 있었던 자리를 건너뛴다", at)
                    .isEqualTo(expected);
        }
    }

    // ------------------------------------------------------------------ 픽스처

    private static RuleSet maxStops(int max) {
        return DispatchRules.ruleSet(List.of(
                new RuleDefinition("max-stops", RuleType.MAX_STOPS_PER_ROUTE, RuleSeverity.HARD, 10,
                        Map.of("max", max))), 1);
    }

    private static RuleSet capacity() {
        return DispatchRules.ruleSet(List.of(
                new RuleDefinition("capacity", RuleType.VEHICLE_CAPACITY, RuleSeverity.HARD, 10,
                        Map.of())), 1);
    }

    private static RuleSet coldChain() {
        return DispatchRules.ruleSet(List.of(
                new RuleDefinition("cold-chain", RuleType.VEHICLE_ATTRIBUTE_MATCH,
                        RuleSeverity.HARD, 10,
                        Map.of("orderFlag", "requiresCold", "vehicleFlag", "isCold"))), 1);
    }

    private static List<Stop> stops(int count) {
        List<Stop> stops = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            stops.add(stop(i));
        }
        return List.copyOf(stops);
    }

    private static Stop stop(int steps) {
        return at(steps, new Parcel(1_000, 2_000, false, false));
    }

    private static Stop cold(int steps) {
        return at(steps, new Parcel(1_000, 2_000, true, false));
    }

    /** 넷이면 용량(40 kg)을 넘는 무게. */
    private static Stop heavy(int steps) {
        return at(steps, new Parcel(11_000, 2_000, false, false));
    }

    private static Stop light(int steps) {
        return at(steps, new Parcel(100, 200, false, false));
    }

    private static Stop at(int steps, Parcel parcel) {
        return new Stop(GeoPoint.of(CAMP.lat(), CAMP.lng() + 0.002d * steps),
                List.of(OrderId.of(Ids.newId())), parcel, WINDOW, 60, 0);
    }

    private static VehicleSpec vehicle() {
        return new VehicleSpec(VehicleId.of(Ids.newId()), new Capacity(40_000, 400_000),
                new VehicleAttrs("VAN", false, false),
                new TimeWindow(START, START.plus(Duration.ofHours(10))),
                VehicleCost.krw(45_000, 600, 250));
    }

    private static PlanningProblem problem(RuleSet rules) {
        return new PlanningProblem(new WaveRef(Ids.newId(), CAMP_ID, "SAME_DAY", START),
                new CampDepot(CAMP_ID, CAMP), List.<Candidate>of(), List.of(vehicle()), rules,
                new CostModel(), new HaversineDistance(1.3d, 25.0d),
                new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(3)),
                PlanMode.FULL, 1.0d, START, 1L);
    }
}
