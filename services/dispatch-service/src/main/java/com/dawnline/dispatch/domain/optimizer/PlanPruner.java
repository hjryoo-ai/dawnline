package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.Money;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 발행 직전에 취소된 주문을 계획에서 뺀다 (DESIGN.md §6.5 6단계, §6.10, ADR-026 분기 2).
 *
 * <h2>왜 여기서 빼는가</h2>
 * 계획은 시작 시점 스냅샷으로 돈다. 그 사이에 도착한 취소는 계획에 반영되지 않았고, 그대로
 * 발행하면 <strong>취소된 주문이 라우트에 실려 나간다.</strong> 발행 뒤에 빼려면 revision 을
 * 하나 써야 하는데, 아직 아무것도 나가지 않았으므로 <em>revision 없이</em> 닫을 수 있는 창이다.
 *
 * <h2>순번을 다시 매긴다</h2>
 * §6.10 은 발행된 라우트의 취소된 stop 에 대해 "seq 를 다시 매기지 않는다" 고 했다. 그건
 * <em>기사가 이미 보고 있는 순번</em>이 바뀌지 않아야 해서다. 여기서는 아직 아무도 보지
 * 않았으므로 다시 매긴다 — 빈 자리를 남기면 {@code route.assigned} 의 "seq 는 1부터 연속"
 * 불변식이 깨진다.
 *
 * <h2>비용은 다시 계산하지 않는다</h2>
 * 취소로 짧아진 경로의 <em>정확한</em> 비용을 알려면 다시 풀어야 하고, 그건 ADR-026 이
 * "취소는 최적화의 트리거가 아니다" 로 거절한 일이다. 라우트 비용은 그대로 두고 미배정 페널티만
 * 뺀다 — 남는 값은 실제보다 <strong>조금 비싸며</strong>, 그 방향의 오차는 안전하다(계획이
 * 실제보다 싸 보이면 전략 비교가 거짓이 된다).
 */
public final class PlanPruner {

    private PlanPruner() {
    }

    /**
     * 취소된 주문을 뺀 결과.
     *
     * @param result    원래 결과
     * @param cancelled 취소된 주문들
     */
    public static PlanResult prune(PlanResult result, Set<OrderId> cancelled) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(cancelled, "cancelled");
        if (cancelled.isEmpty()) {
            return result;
        }

        List<PlannedRoute> routes = new ArrayList<>();
        for (PlannedRoute route : result.routes()) {
            List<PlannedStop> kept = new ArrayList<>();
            for (PlannedStop planned : route.stops()) {
                Stop stop = withoutOrders(planned.stop(), cancelled);
                if (stop != null) {
                    kept.add(new PlannedStop(kept.size() + 1, stop, planned.arrival(),
                            planned.departure()));
                }
            }
            if (!kept.isEmpty()) {
                routes.add(new PlannedRoute(route.vehicle(), List.copyOf(kept), route.distanceM(),
                        route.durationS(), route.cost()));
            }
        }

        List<Unassigned> unassigned = result.unassigned().stream()
                .filter(entry -> !cancelled.contains(entry.orderId()))
                .toList();
        List<Explanation> explanations = result.explanations().stream()
                .filter(entry -> !cancelled.contains(entry.orderId()))
                .toList();

        int assigned = routes.stream().mapToInt(PlannedRoute::orderCount).sum();
        int lateStops = (int) routes.stream().mapToLong(PlannedRoute::lateStopCount).sum();
        long totalLateMinutes = routes.stream().flatMap(route -> route.stops().stream())
                .mapToLong(PlannedStop::lateMinutes).sum();
        PlanMetrics before = result.metrics();
        PlanMetrics metrics = new PlanMetrics(routes.size(), assigned, unassigned.size(),
                routes.size(),
                routes.stream().mapToLong(PlannedRoute::distanceM).sum(),
                routes.stream().mapToLong(PlannedRoute::durationS).sum(),
                lateStops, totalLateMinutes, before.planDurationMs());

        Money cost = routes.stream().map(PlannedRoute::cost).reduce(Money.ZERO, Money::plus);
        return new PlanResult(routes, unassigned, cost, metrics, explanations);
    }

    /** 취소된 주문을 뺀 stop. 남은 주문이 없으면 {@code null}. */
    private static Stop withoutOrders(Stop stop, Set<OrderId> cancelled) {
        Set<OrderId> kept = new LinkedHashSet<>(stop.orderIds());
        if (!kept.removeAll(cancelled)) {
            return stop;
        }
        if (kept.isEmpty()) {
            return null;
        }
        // 화물과 서비스 시간은 줄지만 다시 계산하지 않는다 — 정확히 알려면 통합을 되돌려야 하고,
        // 남는 값은 실제보다 조금 크다. 그 방향의 오차는 용량 판정에서 안전한 쪽이다.
        return new Stop(stop.point(), List.copyOf(kept), stop.parcel(), stop.promised(),
                stop.serviceSeconds(), stop.priority());
    }
}
