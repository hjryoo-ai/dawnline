package com.dawnline.dispatch.domain.optimizer.strategy;

import com.dawnline.dispatch.domain.optimizer.PlanningProblem;
import com.dawnline.dispatch.domain.optimizer.RouteAccumulator;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 「이 순서로 실으면 되는가, 얼마인가」를 <strong>실제로 실어 보고</strong> 답한다.
 *
 * <h2>왜 근사식이 아닌가</h2>
 * §6.3 이 룰을 데이터로 둔 이상, 비용 근사식은 <em>룰을 코드에 두 번째로 적는 일</em>이다.
 * 지각 페널티·{@code PRIORITY_BOOST}·{@code ZONE_AFFINITY} 는 배치 순번과 도착 시각에 달렸고,
 * 그것을 식으로 옮기면 룰을 고칠 때마다 두 곳을 고쳐야 한다 — 그리고 한 곳을 잊는 날 최적화는
 * 자기가 만든 답이 왜 좋은지 <strong>틀리게 설명한다</strong>.
 *
 * <p>개선 단계({@link LocalSearchImprover})와 미배정 재삽입({@link UnassignedRepair})이 같은
 * 판정을 쓰도록 여기 모아 둔다. 둘이 각자 재면 "국소 탐색이 실을 수 있다고 본 라우트를 재삽입은
 * 못 싣는다" 같은 일이 생긴다.
 */
final class RouteRebuild {

    /** 하드 룰을 어기는 순서. 합산 전에 걸러야 하므로 더하지 않는다. */
    static final long INFEASIBLE = Long.MAX_VALUE;

    private RouteRebuild() {
    }

    /**
     * 이 순서로 라우트를 다시 만든다.
     *
     * @return 하드 룰을 어기면 {@code null}
     */
    static @Nullable RouteAccumulator accumulate(PlanningProblem problem, VehicleSpec vehicle,
            List<Stop> stops) {

        RouteAccumulator route = new RouteAccumulator(problem.rules(), vehicle, problem.depot(),
                problem.distance(), problem.startedAt());
        for (Stop stop : stops) {
            if (!route.check(stop).feasible()) {
                return null;
            }
            route.append(stop);
        }
        return route;
    }

    /**
     * 이 순서의 비용. 빈 라우트는 0 이다 — 굴리지 않은 차는 고정비를 물지 않는다 (§6.4).
     *
     * @return 하드 룰을 어기면 {@link #INFEASIBLE}
     */
    static long cost(PlanningProblem problem, VehicleSpec vehicle, List<Stop> stops) {
        if (stops.isEmpty()) {
            return 0L;
        }
        RouteAccumulator route = accumulate(problem, vehicle, stops);
        return route == null ? INFEASIBLE : route.toRoute(problem.cost()).cost().krw();
    }

    /** 둘 중 하나라도 실행 불가면 실행 불가다. 오버플로 없이 합친다. */
    static long combined(long left, long right) {
        return left == INFEASIBLE || right == INFEASIBLE ? INFEASIBLE : left + right;
    }
}
