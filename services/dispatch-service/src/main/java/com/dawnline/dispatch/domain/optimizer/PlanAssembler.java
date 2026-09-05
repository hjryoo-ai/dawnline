package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 라우트와 남은 stop 을 {@link PlanResult} 로 조립한다.
 *
 * <h2>왜 공용인가</h2>
 * 지표 계산(§6.9)과 목적함수 합산(§6.1), 미배정을 주문 단위로 펼치는 일은 <strong>전략과 무관</strong>
 * 하다. 전략마다 따로 쓰면 "전략 A 의 비용" 과 "전략 B 의 비용" 이 서로 다른 방식으로 계산되고,
 * 그러면 §6.9 의 비교표가 비교가 아니게 된다.
 */
public final class PlanAssembler {

    private PlanAssembler() {
    }

    /**
     * 조립한다.
     *
     * @param problem      계획 입력
     * @param routes       확정된 라우트들
     * @param unassigned   배정되지 못한 stop 들
     * @param refusals     stop 별 마지막 불가 사유. 없으면 기본 사유를 쓴다
     * @param explanations 배정 설명들. 미배정 설명은 여기서 덧붙인다
     */
    public static PlanResult assemble(PlanningProblem problem, List<PlannedRoute> routes,
            List<Stop> unassigned, Map<Stop, Feasibility> refusals,
            List<Explanation> explanations) {

        Objects.requireNonNull(problem, "problem");
        List<Explanation> allExplanations = new ArrayList<>(explanations);
        List<Unassigned> unassignedOrders = new ArrayList<>();
        Money unassignedPenalty = Money.ZERO;

        for (Stop stop : unassigned) {
            Feasibility refusal = refusals.getOrDefault(stop, Feasibility.violated(
                    "no-feasible-vehicle", "실을 수 있는 차량이 없습니다"));
            for (OrderId orderId : stop.orderIds()) {
                unassignedOrders.add(Unassigned.from(orderId, refusal));
                allExplanations.add(
                        Explanation.unassigned(orderId, refusal, problem.vehicles().size()));
            }
            unassignedPenalty = unassignedPenalty.plus(problem.rules().unassignedPenalty(stop));
        }

        Money routeCosts = routes.stream().map(PlannedRoute::cost).reduce(Money.ZERO, Money::plus);
        int assignedOrders = routes.stream().mapToInt(PlannedRoute::orderCount).sum();
        int lateStops = (int) routes.stream().mapToLong(PlannedRoute::lateStopCount).sum();
        long totalLateMinutes = routes.stream().flatMap(route -> route.stops().stream())
                .mapToLong(PlannedStop::lateMinutes).sum();

        PlanMetrics metrics = new PlanMetrics(routes.size(), assignedOrders,
                unassignedOrders.size(), routes.size(),
                routes.stream().mapToLong(PlannedRoute::distanceM).sum(),
                routes.stream().mapToLong(PlannedRoute::durationS).sum(),
                lateStops, totalLateMinutes,
                // 계획 시간은 순수 함수가 알 수 없다 — 재는 쪽(RunPlan·벤치마크)이 채운다.
                0L);

        // §6.1 의 목적함수: 라우트 비용(고정·거리·시간 + 소프트 페널티) + 미배정 페널티.
        return new PlanResult(routes, unassignedOrders, routeCosts.plus(unassignedPenalty),
                metrics, allExplanations);
    }
}
