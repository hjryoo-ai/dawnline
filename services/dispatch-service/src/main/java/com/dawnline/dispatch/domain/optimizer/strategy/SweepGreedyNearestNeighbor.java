package com.dawnline.dispatch.domain.optimizer.strategy;

import com.dawnline.dispatch.domain.optimizer.Capacity;
import com.dawnline.dispatch.domain.optimizer.DispatchStrategy;
import com.dawnline.dispatch.domain.optimizer.DistanceProvider;
import com.dawnline.dispatch.domain.optimizer.Explanation;
import com.dawnline.dispatch.domain.optimizer.Feasibility;
import com.dawnline.dispatch.domain.optimizer.PlanAssembler;
import com.dawnline.dispatch.domain.optimizer.PlanResult;
import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import com.dawnline.dispatch.domain.optimizer.PlannedStop;
import com.dawnline.dispatch.domain.optimizer.PlanningProblem;
import com.dawnline.dispatch.domain.optimizer.RouteAccumulator;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.StopMerger;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * §6.5 의 1~4단계 — 통합 → 스윕 클러스터링 → 탐욕 차량 할당 → 시간창 최근접 이웃
 * (DESIGN.md §6.6 {@code sweep-greedy-nn}).
 *
 * <h2>{@code baseline-nn} 과 다른 세 가지</h2>
 * <ol>
 *   <li><strong>극각으로 자른다</strong>({@link SweepClusterer}). 최근접만 쓰면 각을 무시하고
 *       가까운 곳으로 가다가 반대편으로 건너뛴다.</li>
 *   <li><strong>차량을 고른다</strong>({@link GreedyAssigner}). 베이스라인은 차를 순서대로
 *       꺼내지만, 여기서는 한계비용이 가장 작은 차에 붙인다 — 고정비를 실제로 아끼는 자리다.
 *       제약이 있는 주문(냉장·위험물)이 자리를 잃는 것도 여기서 줄어든다.</li>
 *   <li><strong>시간을 본다</strong>({@link NearestNeighborSequencer}). 거리만이 아니라 대기와
 *       지각 페널티를 함께 최소화한다.</li>
 * </ol>
 *
 * <h2>개선 단계는 켜고 끈다</h2>
 * §6.5 5단계({@link LocalSearchImprover})가 붙은 것이 {@code sweep-greedy-nn+ls} 이고, 나머지는
 * 전부 같다. <strong>두 클래스로 나누지 않는 이유</strong>는 1~4단계가 갈라지면 §6.9 의 비교표가
 * "개선 단계의 값어치" 가 아니라 "두 구현의 차이" 를 재게 되기 때문이다. 같은 이유로 §6.7 의
 * 열화 모드(FAST)도 이 자리를 끄는 것으로 표현된다.
 */
public final class SweepGreedyNearestNeighbor implements DispatchStrategy {

    /** 전략 이름 (§6.6). */
    public static final String NAME = "sweep-greedy-nn";

    /** 개선 단계를 붙인 전략 이름 (§6.6, Phase 4-1). */
    public static final String NAME_WITH_LOCAL_SEARCH = "sweep-greedy-nn+ls";

    /** 권역 경계 자르기를 허용하기 시작하는 클러스터 크기. */
    private static final int MIN_STOPS_BEFORE_ZONE_CUT = 8;

    private final SweepClusterer clusterer = new SweepClusterer(MIN_STOPS_BEFORE_ZONE_CUT);
    private final NearestNeighborSequencer sequencer = new NearestNeighborSequencer();
    private final GreedyAssigner assigner = new GreedyAssigner(sequencer);

    private final @Nullable LocalSearchImprover improver;

    /** 1~4단계만. */
    public SweepGreedyNearestNeighbor() {
        this(null);
    }

    private SweepGreedyNearestNeighbor(@Nullable LocalSearchImprover improver) {
        this.improver = improver;
    }

    /** 5단계(국소 탐색)까지. */
    public static SweepGreedyNearestNeighbor withLocalSearch() {
        return new SweepGreedyNearestNeighbor(new LocalSearchImprover());
    }

    @Override
    public String name() {
        return improver == null ? NAME : NAME_WITH_LOCAL_SEARCH;
    }

    @Override
    public PlanResult plan(PlanningProblem problem) {
        // 경과 시간을 재는 스톱워치다 — 시각을 묻지 않으므로 불변규칙 12 의 대상이 아니고,
        // 물어서도 안 된다(계획 시각은 problem.startedAt() 이 이미 들고 있는 입력이다).
        long startedNanos = System.nanoTime();
        List<Stop> stops = StopMerger.merge(problem.candidates());
        DistanceProvider distance = problem.distance();

        List<List<Stop>> clusters =
                clusterer.cluster(stops, problem.depot(), largestCapacity(problem),
                        problem.vehicles().size());

        List<RouteAccumulator> routes = problem.vehicles().stream()
                .map(vehicle -> new RouteAccumulator(problem.rules(), vehicle, problem.depot(),
                        distance, problem.startedAt()))
                .toList();

        Map<Stop, Feasibility> refusals = new LinkedHashMap<>();
        List<Stop> unassigned = assigner.assign(clusters, routes, problem.depot(), distance,
                problem.cost(), problem.startedAt(), refusals);

        // 3단계가 남긴 것을 규칙으로 다시 싣는다 (ADR-028). 클러스터 단위 배정은 "이 묶음이
        // 이 차에 들어가는가" 만 묻기 때문에, 제약이 붙은 stop 하나 때문에 나머지가 통째로
        // 밀려나는 일이 생긴다 — 그 stop 을 stop 단위로 다시 보는 것이 여기다.
        UnassignedRepair.Outcome repaired = UnassignedRepair.repair(problem, routes, unassigned);
        List<RouteAccumulator> finished = repaired.routes();
        unassigned = repaired.unassigned();

        if (improver != null) {
            finished = improver.improve(problem, finished,
                    System.nanoTime() - startedNanos).routes();
            // 개선 단계는 <strong>자리를 만든다</strong> — 라우트가 짧아지면 근무창·약속창에
            // 여유가 생긴다. 그래서 한 번 더 본다. 재삽입 자체는 개선이 아니라 값싼 탐욕이다.
            UnassignedRepair.Outcome again = UnassignedRepair.repair(problem, finished, unassigned);
            finished = again.routes();
            unassigned = again.unassigned();
        }

        List<PlannedRoute> planned = new ArrayList<>();
        List<Explanation> explanations = new ArrayList<>();
        for (int i = 0; i < finished.size(); i++) {
            RouteAccumulator route = finished.get(i);
            if (route.isEmpty()) {
                continue;                       // 빈 라우트는 만들지 않는다 (고정비를 물지 않는다)
            }
            PlannedRoute result = route.toRoute(problem.cost());
            planned.add(result);
            VehicleSpec vehicle = problem.vehicles().get(i);
            for (PlannedStop stop : result.stops()) {
                stop.stop().orderIds().forEach(orderId -> explanations.add(
                        Explanation.assigned(orderId, vehicle.id(), 0L)));
            }
        }

        return PlanAssembler.assemble(problem, planned, unassigned, refusals, explanations);
    }

    /** 가장 큰 차량의 용량. 클러스터가 이보다 크면 어떤 차도 실을 수 없다. */
    private static Capacity largestCapacity(PlanningProblem problem) {
        return problem.vehicles().stream().map(VehicleSpec::capacity)
                .max(Comparator.comparingLong(capacity ->
                        (long) capacity.maxWeightG() + capacity.maxVolumeCm3()))
                .orElseThrow(() -> new IllegalArgumentException("차량이 하나도 없습니다"));
    }
}
