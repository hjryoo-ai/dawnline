package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.Money;
import java.util.List;
import java.util.Objects;

/**
 * 계획 한 번의 결과 (DESIGN.md §6.2).
 *
 * <p>{@code totalCost} 는 라우트 비용의 합 + 미배정 페널티 + 소프트 룰 페널티다(§6.1). 전략끼리
 * 비교하는 값이 이것이고, §6.9 의 회귀 게이트가 보는 값도 이것이다.
 *
 * @param routes       확정된 라우트들
 * @param unassigned   배정되지 못한 주문들
 * @param totalCost    총비용
 * @param metrics      지표
 * @param explanations 설명 (§6.3)
 */
public record PlanResult(List<PlannedRoute> routes, List<Unassigned> unassigned, Money totalCost,
        PlanMetrics metrics, List<Explanation> explanations) {

    public PlanResult {
        Objects.requireNonNull(totalCost, "totalCost");
        Objects.requireNonNull(metrics, "metrics");
        routes = List.copyOf(Objects.requireNonNull(routes, "routes"));
        unassigned = List.copyOf(Objects.requireNonNull(unassigned, "unassigned"));
        explanations = List.copyOf(Objects.requireNonNull(explanations, "explanations"));
    }

    /** 배정된 주문 수. */
    public int assignedOrderCount() {
        return routes.stream().mapToInt(PlannedRoute::orderCount).sum();
    }
}
