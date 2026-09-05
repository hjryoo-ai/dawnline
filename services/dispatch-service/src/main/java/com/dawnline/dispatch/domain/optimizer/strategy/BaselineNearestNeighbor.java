package com.dawnline.dispatch.domain.optimizer.strategy;

import com.dawnline.common.Money;
import com.dawnline.dispatch.domain.optimizer.CampDepot;
import com.dawnline.dispatch.domain.optimizer.DispatchStrategy;
import com.dawnline.dispatch.domain.optimizer.DistanceProvider;
import com.dawnline.dispatch.domain.optimizer.Explanation;
import com.dawnline.dispatch.domain.optimizer.Feasibility;
import com.dawnline.dispatch.domain.optimizer.OrderId;
import com.dawnline.dispatch.domain.optimizer.PlanAssembler;
import com.dawnline.dispatch.domain.optimizer.PlanResult;
import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import com.dawnline.dispatch.domain.optimizer.PlanningProblem;
import com.dawnline.dispatch.domain.optimizer.RouteAccumulator;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.StopMerger;
import com.dawnline.dispatch.domain.optimizer.Travel;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 클러스터링 없이 최근접 이웃만 쓰는 기준선 (DESIGN.md §6.6 {@code baseline-nn}).
 *
 * <h2>이것이 기준선인 이유</h2>
 * §6.9 의 회귀 게이트가 "기본 전략 비용이 베이스라인보다 나쁘면 실패" 이므로, 기준선은
 * <strong>가장 단순한 진짜 계획</strong>이어야 한다. 클러스터링도, 차량 선택도, 개선도 없다 —
 * 차를 순서대로 꺼내 가장 가까운 실을 수 있는 곳으로 계속 가고, 더 못 가면 다음 차를 꺼낸다.
 *
 * <h2>고르는 기준은 거리 하나다</h2>
 * 소프트 페널티를 선택에 넣으면 그것은 이미 "비용 기반 탐욕" 이지 최근접 이웃이 아니다. 페널티는
 * <em>비용에 계산되지만 선택을 바꾸지는 않는다</em> — 그래야 {@code sweep-greedy-nn} 이 페널티를
 * 보고 고르기 시작했을 때 그 차이가 표에 나타난다.
 *
 * <h2>동률</h2>
 * 같은 거리의 stop 이 둘이면 <strong>먼저 온 것</strong>을 고른다. {@link StopMerger} 가 입력
 * 순서를 유지하므로 같은 seed 는 같은 결과를 낸다(불변규칙 12).
 *
 * <h2>거리 캐시를 두지 않는다</h2>
 * 최근접 탐색은 같은 지점 쌍을 여러 번 물어보므로 캐시가 이득일 것 같았다. <strong>측정이
 * 반대였다</strong> — geohash7 로 키를 잡은 캐시를 넣었더니 small 19→116 ms, medium 170→1,238 ms 로
 * <em>6~7배 느려졌다</em>(해시 조회와 키 조립이 하버사인 한 번보다 비싸다). 게다가 geohash7 은
 * 약 153 m 격자라 <strong>답이 달라졌다</strong> — 같은 셀의 서로 다른 stop 이 같은 거리를 받아
 * {@code PlanValidator} 가 "지각 61분이 상한 60분을 넘긴다" 를 잡았다.
 *
 * <p>좌표쌍을 정확히 키로 잡으면 답은 맞지만 large 에서 500만 쌍이 되어 §6.7 의 메모리 예산을
 * 위협한다. 그래서 <strong>캐시하지 않는다</strong> — §6.7 이 적어 둔 그대로다("하버사인은 즉시
 * 계산 가능하나 OSRM 사용 시 캐시 필수"). OSRM 캐시는 Redis 이고 어댑터의 일이다.
 * 수치는 {@code docs/benchmarks/phase3-baseline.md}.
 */
public final class BaselineNearestNeighbor implements DispatchStrategy {

    /** 전략 이름 (§6.6). */
    public static final String NAME = "baseline-nn";

    /** 어떤 차량에도 실을 수 없어 남은 stop 의 사유. */
    private static final String NO_VEHICLE = "no-feasible-vehicle";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PlanResult plan(PlanningProblem problem) {
        List<Stop> stops = StopMerger.merge(problem.candidates());
        DistanceProvider distance = problem.distance();

        Set<Stop> remaining = new LinkedHashSet<>(stops);
        List<PlannedRoute> routes = new ArrayList<>();
        List<Explanation> explanations = new ArrayList<>();
        // 마지막으로 본 불가 사유. 남은 stop 의 설명에 쓴다.
        Map<Stop, Feasibility> lastRefusal = new LinkedHashMap<>();

        for (VehicleSpec vehicle : problem.vehicles()) {
            if (remaining.isEmpty()) {
                break;
            }
            RouteAccumulator route = new RouteAccumulator(problem.rules(), vehicle,
                    problem.depot(), distance, problem.startedAt());

            while (true) {
                Stop nearest = nearestFeasible(route, remaining, distance, lastRefusal);
                if (nearest == null) {
                    break;
                }
                Money marginal = route.penaltyOf(nearest);
                route.append(nearest);
                remaining.remove(nearest);
                lastRefusal.remove(nearest);
                nearest.orderIds().forEach(orderId ->
                        explanations.add(Explanation.assigned(orderId, vehicle.id(), marginal.krw())));
            }

            if (!route.isEmpty()) {
                routes.add(route.toRoute(problem.cost()));
            }
        }

        return PlanAssembler.assemble(problem, routes, List.copyOf(remaining), lastRefusal,
                explanations);
    }

    /**
     * 지금 위치에서 가장 가까운 <strong>실을 수 있는</strong> stop. 없으면 {@code null}.
     *
     * <p>불가 사유를 함께 기록한다 — 끝까지 남은 stop 의 "왜 미배정인가" 가 그 값이다(§6.3).
     */
    private Stop nearestFeasible(RouteAccumulator route, Set<Stop> remaining,
            DistanceProvider distance, Map<Stop, Feasibility> lastRefusal) {

        Stop best = null;
        int bestMeters = Integer.MAX_VALUE;
        for (Stop stop : remaining) {
            Feasibility feasibility = route.check(stop);
            if (!feasibility.feasible()) {
                lastRefusal.put(stop, feasibility);
                continue;
            }
            Travel travel = distance.between(route.state().at(), stop.point());
            // 엄격한 부등호라 동률이면 먼저 온 것이 이긴다 — 순서가 결정적이므로 결과도 결정적이다.
            if (travel.meters() < bestMeters) {
                best = stop;
                bestMeters = travel.meters();
            }
        }
        return best;
    }

}
