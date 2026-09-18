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
import com.dawnline.dispatch.domain.optimizer.PlanResult;
import com.dawnline.dispatch.domain.optimizer.PlanValidator;
import com.dawnline.dispatch.domain.optimizer.PlannedStop;
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
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 국소 탐색 (§6.5 5단계).
 *
 * <h2>무엇을 고정하는가</h2>
 * 이 단계는 <em>이미 만들어진 답</em>을 고쳐 쓰는 자리라, 잘못되면 좋아 보이는 답이 나온다 —
 * stop 을 잃거나, 하드 룰을 어기거나, 같은 입력에 다른 답을 낸다. 셋 다 조용하다. 그래서
 * 여기 있는 테스트의 절반은 "얼마나 좋아졌는가" 가 아니라 <strong>무엇이 망가지지 않았는가</strong>다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("LocalSearchImprover — 개선 단계")
class LocalSearchImproverTest {

    private static final Instant START = Instant.parse("2026-09-06T01:00:00Z");
    private static final GeoPoint CAMP = GeoPoint.of(37.5663, 126.9779);
    private static final UUID CAMP_ID = Ids.newId();
    private static final TimeWindow WINDOW = new TimeWindow(START, START.plus(Duration.ofHours(8)));

    private final LocalSearchImprover improver = new LocalSearchImprover();

    // ------------------------------------------------------------------ 개선한다

    @Test
    void 교차한_방문_순서를_편다() {
        // 캠프에서 동쪽으로 일렬로 늘어선 네 곳. 최적은 가까운 것부터이고, 씨앗은 2·3을
        // 뒤바꿔 넣었다 — 2-opt 가 정확히 이 형태를 편다.
        List<Stop> line = List.of(east(1), east(3), east(2), east(4));
        PlanningProblem problem = problem(RuleSet.empty());
        VehicleSpec vehicle = vehicle();

        RouteAccumulator improved = improver
                .improve(problem, List.of(seeded(problem, vehicle, line)), 0L)
                .routes().getFirst();

        assertThat(longitudes(improved))
                .as("일직선 위에서 최적 순서는 단조 증가다 — 교차가 남아 있으면 이 순서가 깨진다")
                .isSorted();
    }

    @Test
    void 라우트를_비울_수_있으면_비운다() {
        // 차 두 대. 두 번째는 stop 하나만 들었고 그 stop 은 첫 라우트 바로 옆이다.
        // 옮기면 두 번째 차의 고정비가 통째로 사라진다 — 국소 탐색이 고정비를 줄이는
        // 유일한 길이다(§6.4: 빈 라우트는 고정비를 물지 않는다).
        PlanningProblem problem = problem(RuleSet.empty());
        VehicleSpec first = vehicle();
        VehicleSpec second = vehicle();
        List<RouteAccumulator> seeded = List.of(
                seeded(problem, first, List.of(east(1), east(2), east(3))),
                seeded(problem, second, List.of(east(2))));

        List<RouteAccumulator> improved = improver.improve(problem, seeded, 0L).routes();

        // <strong>어느 쪽이 비는지는 묻지 않는다.</strong> 두 차의 스펙이 같으니 그건 동률이고,
        // 동률의 답을 어설션에 적으면 그 테스트는 규칙이 아니라 우연을 고정한다(ADR-031).
        assertThat(improved).filteredOn(RouteAccumulator::isEmpty)
                .as("한 대는 비어야 한다 — 고정비 %d원을 굴리지 않고 아낀다",
                        second.cost().fixed().krw())
                .hasSize(1);
        assertThat(improved.stream().mapToInt(route -> route.state().stopCount()).sum())
                .as("비운 쪽의 stop 은 다른 라우트로 갔지 사라진 것이 아니다")
                .isEqualTo(4);
    }

    // ------------------------------------------------------------------ 망가뜨리지 않는다

    @Test
    void 개선은_stop_을_잃지도_더하지도_않는다() {
        PlanningProblem problem = problem(RuleSet.empty());
        List<RouteAccumulator> seeded = List.of(
                seeded(problem, vehicle(), List.of(east(1), east(4), east(2))),
                seeded(problem, vehicle(), List.of(east(3), east(6), east(5))));

        List<OrderId> before = orderIds(seeded);
        List<OrderId> after = orderIds(improver.improve(problem, seeded, 0L).routes());

        assertThat(after)
                .as("이 단계는 순서와 소속만 바꾼다. 집합이 달라지면 주문이 사라졌거나 두 번 실렸다")
                .containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    void 개선한_라우트는_하드_룰을_다시_통과한다() {
        // §6.5 6단계가 개선 단계 때문에 존재한다. 그 방어선이 실제로 통과하는지를 여기서 본다 —
        // 룰이 살아 있는 문제로 전체 파이프라인을 돌린다.
        RuleSet rules = DispatchRules.ruleSet(List.of(
                new RuleDefinition("cold-chain", RuleType.VEHICLE_ATTRIBUTE_MATCH,
                        RuleSeverity.HARD, 10,
                        Map.of("orderFlag", "requiresCold", "vehicleFlag", "isCold")),
                new RuleDefinition("capacity", RuleType.VEHICLE_CAPACITY, RuleSeverity.HARD, 20,
                        Map.of()),
                new RuleDefinition("max-stops", RuleType.MAX_STOPS_PER_ROUTE, RuleSeverity.HARD, 30,
                        Map.of("max", 8)),
                new RuleDefinition("shift", RuleType.SHIFT_WINDOW, RuleSeverity.HARD, 40,
                        Map.of("bufferMinutes", 30)),
                new RuleDefinition("late", RuleType.TIME_WINDOW_PENALTY, RuleSeverity.SOFT, 50,
                        Map.of("penaltyPerMinuteKrw", 300))), 1);

        PlanningProblem problem = problem(rules, mixedOrders(),
                List.of(vehicle(true), vehicle(false), vehicle(false)));
        PlanResult result = SweepGreedyNearestNeighbor.withLocalSearch().plan(problem);

        assertThat(result.routes()).as("전제: 라우트가 있어야 검증할 것이 있다").isNotEmpty();
        assertThat(new PlanValidator().validate(problem, result))
                .as("개선 뒤에도 하드 룰 위반은 0 이어야 한다")
                .isEmpty();
    }

    @Test
    void 같은_입력이면_같은_결과다() {
        PlanningProblem problem = problem(RuleSet.empty());
        List<Stop> stops = List.of(east(1), east(5), east(2), east(4), east(3));
        VehicleSpec vehicle = vehicle();

        List<Double> once = longitudes(improver
                .improve(problem, List.of(seeded(problem, vehicle, stops)), 0L).routes().getFirst());
        List<Double> twice = longitudes(improver
                .improve(problem, List.of(seeded(problem, vehicle, stops)), 0L).routes().getFirst());

        assertThat(twice).as("불변규칙 12 — 예산 안에서 끝나면 결과가 흔들리지 않는다")
                .isEqualTo(once);
    }

    // ------------------------------------------------------------------ 예산

    @Test
    void 예산이_남지_않았으면_그리디_결과를_그대로_돌려준다() {
        PlanningProblem problem = problem(RuleSet.empty());
        List<Stop> stops = List.of(east(1), east(3), east(2), east(4));
        RouteAccumulator seeded = seeded(problem, vehicle(), stops);

        LocalSearchImprover.Outcome outcome = improver.improve(problem, List.of(seeded),
                problem.budget().total().toNanos());

        assertThat(outcome.budgetExhausted()).isTrue();
        assertThat(outcome.passes()).isZero();
        assertThat(longitudes(outcome.routes().getFirst()))
                .as("한 패스도 못 돌았으면 씨앗 그대로여야 한다 — FAST 모드가 이 자리다(§6.7)")
                .isEqualTo(longitudes(seeded));
    }

    @Test
    void 패스_중간에_예산이_끝나면_그_패스를_통째로_버린다() {
        PlanningProblem problem = problem(RuleSet.empty());
        List<Stop> stops = List.of(east(1), east(5), east(2), east(6), east(3), east(4));
        VehicleSpec vehicle = vehicle();

        assertThat(longitudes(improver.improve(problem, List.of(seeded(problem, vehicle, stops)), 0L)
                .routes().getFirst()))
                .as("전제: 예산이 넉넉하면 이 씨앗은 실제로 바뀐다. 바뀌지 않는다면 아래 검사는 "
                        + "「버렸다」가 아니라 「할 일이 없었다」를 보게 된다")
                .isNotEqualTo(longitudes(seeded(problem, vehicle, stops)));

        // 네 번째 호출부터 예산을 넘긴다 — 그 지점에서 이미 이동 하나가 적용돼 있다.
        AtomicLong calls = new AtomicLong();
        LocalSearchImprover stopwatch = new LocalSearchImprover(
                () -> calls.getAndIncrement() < 4L ? 0L : Duration.ofMinutes(1).toNanos());

        LocalSearchImprover.Outcome outcome =
                stopwatch.improve(problem, List.of(seeded(problem, vehicle, stops)), 0L);

        assertThat(outcome.budgetExhausted()).isTrue();
        assertThat(outcome.passes()).as("끝까지 돈 패스가 없다").isZero();
        assertThat(longitudes(outcome.routes().getFirst()))
                .as("반쯤 돈 패스는 버린다 — 결과는 결정적인 수열의 한 원소여야 하고, "
                        + "예산은 어디까지 갔는지만 정한다")
                .isEqualTo(longitudes(seeded(problem, vehicle, stops)));
    }

    @Test
    void 개선_예산_계수가_마감을_당긴다() {
        // §6.7 사다리의 아랫단 (ADR-034 후속 정정). BUDGET 사유의 처방은 개선을 <em>끄는</em> 것이
        // 아니라 <em>덜 하는</em> 것이고, 그것이 실제로 일어나려면 계수가 마감에 닿아야 한다.
        //
        // 벽시계로 재지 않는다 — 계수는 산술의 문제다. 시계를 「첫 호출은 0, 그 뒤로는 언제나
        // 예산의 3/4」로 고정하면 계수 1.0(마감 30초)에서는 아직 시간이 남고 계수 0.5(마감 15초)
        // 에서는 이미 지난 상태가 되어, 갈리는 것이 오직 계수뿐이다.
        long total = Duration.ofSeconds(30).toNanos();
        AtomicLong calls = new AtomicLong();
        LocalSearchImprover fixed =
                new LocalSearchImprover(() -> calls.getAndIncrement() == 0L ? 0L : total * 3 / 4);
        List<Stop> stops = List.of(east(1), east(3), east(2), east(4));

        PlanningProblem whole = problem(RuleSet.empty(), List.of(), List.of(vehicle()), 1.0d);
        LocalSearchImprover.Outcome full =
                fixed.improve(whole, List.of(seeded(whole, vehicle(), stops)), 0L);

        assertThat(full.passes())
                .as("전제: 계수 1.0 이면 이 시계로도 개선이 돈다. 돌지 않으면 아래 검사는 "
                        + "「계수가 마감을 당겼다」가 아니라 「시계가 이미 지났다」를 본다")
                .isPositive();

        calls.set(0L);
        PlanningProblem half = problem(RuleSet.empty(), List.of(), List.of(vehicle()), 0.5d);
        LocalSearchImprover.Outcome halved =
                fixed.improve(half, List.of(seeded(half, vehicle(), stops)), 0L);

        assertThat(halved.budgetExhausted()).isTrue();
        assertThat(halved.passes()).isZero();
        assertThat(longitudes(halved.routes().getFirst()))
                .as("예산이 반이면 이 시계에서는 한 패스도 못 돈다 — 씨앗 그대로여야 한다")
                .isEqualTo(longitudes(seeded(half, vehicle(), stops)));
    }

    @Test
    void 계수는_개선_예산에만_곱해진다() {
        // 「개선 예산」은 total − 앞 단계가 쓴 시간이다. 계수를 total 에 곱하면 그리디가 오래
        // 걸린 날 개선이 음수 예산을 받는다.
        PlanningProblem half = problem(RuleSet.empty(), List.of(), List.of(vehicle()), 0.5d);
        long elapsed = Duration.ofSeconds(10).toNanos();

        assertThat(half.improvementNanos(elapsed))
                .as("(30초 − 10초) × 0.5 = 10초")
                .isEqualTo(Duration.ofSeconds(10).toNanos());
        assertThat(half.improvementNanos(Duration.ofSeconds(40).toNanos()))
                .as("이미 예산을 넘겼으면 0 이다 — 음수 마감은 과거가 된다").isZero();
    }

    // ------------------------------------------------------------------ 재료

    /** 캠프에서 동쪽으로 {@code steps} 칸(약 0.9 km) 떨어진 지점. */
    private static Stop east(int steps) {
        return new Stop(GeoPoint.of(CAMP.lat(), CAMP.lng() + 0.01d * steps),
                List.of(OrderId.of(Ids.newId())), new Parcel(1_000, 2_000, false, false),
                WINDOW, 60, 0);
    }

    private static List<Candidate> mixedOrders() {
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            double radians = Math.toRadians(i * 15.0d);
            GeoPoint point = GeoPoint.of(CAMP.lat() + 0.02d * Math.cos(radians),
                    CAMP.lng() + 0.02d * Math.sin(radians));
            candidates.add(new Candidate(OrderId.of(Ids.newId()), point,
                    new Parcel(2_000, 4_000, i % 4 == 0, false), WINDOW, 60, 0));
        }
        return List.copyOf(candidates);
    }

    private static VehicleSpec vehicle() {
        return vehicle(false);
    }

    private static VehicleSpec vehicle(boolean cold) {
        return new VehicleSpec(VehicleId.of(Ids.newId()), new Capacity(400_000, 1_200_000),
                new VehicleAttrs("VAN", cold, false),
                new TimeWindow(START, START.plus(Duration.ofHours(10))),
                VehicleCost.krw(45_000, 600, 250));
    }

    private static RouteAccumulator seeded(PlanningProblem problem, VehicleSpec vehicle,
            List<Stop> stops) {

        RouteAccumulator route = new RouteAccumulator(problem.rules(), vehicle, problem.depot(),
                problem.distance(), problem.startedAt());
        stops.forEach(route::append);
        return route;
    }

    private static List<Double> longitudes(RouteAccumulator route) {
        return route.state().stops().stream().map(planned -> planned.stop().point().lng()).toList();
    }

    private static List<OrderId> orderIds(List<RouteAccumulator> routes) {
        return routes.stream()
                .flatMap(route -> route.state().stops().stream())
                .map(PlannedStop::stop)
                .flatMap(stop -> stop.orderIds().stream())
                .toList();
    }

    private static PlanningProblem problem(RuleSet rules) {
        return problem(rules, List.of(), List.of(vehicle()));
    }

    private static PlanningProblem problem(RuleSet rules, List<Candidate> candidates,
            List<VehicleSpec> vehicles) {
        return problem(rules, candidates, vehicles, 1.0d);
    }

    private static PlanningProblem problem(RuleSet rules, List<Candidate> candidates,
            List<VehicleSpec> vehicles, double budgetFactor) {

        return new PlanningProblem(new WaveRef(Ids.newId(), CAMP_ID, "SAME_DAY", START),
                new CampDepot(CAMP_ID, CAMP), candidates, vehicles, rules, new CostModel(),
                new HaversineDistance(1.3d, 25.0d),
                new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(3)), PlanMode.FULL,
                budgetFactor, START, 1L);
    }
}
