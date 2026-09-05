package com.dawnline.benchmark;

import com.dawnline.dispatch.domain.optimizer.CostModel;
import com.dawnline.dispatch.domain.optimizer.PlanResult;
import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import com.dawnline.dispatch.domain.optimizer.PlanningProblem;
import com.dawnline.dispatch.domain.optimizer.VehicleId;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 총비용을 항으로 나눈다 (DESIGN.md §6.1 목적함수).
 *
 * <pre>
 * cost(R) = Σ_r [ fixed(v) + dist_km·perKm(v) + dur_min·perMin(v) + 소프트 페널티 ]
 *         + Σ_{o ∉ R} unassignedPenalty(o)
 * </pre>
 *
 * <h2>도메인을 고치지 않고 복원한다</h2>
 * {@code PlannedRoute.cost} 는 차량 비용과 소프트 페널티의 <em>합</em>이라 그대로는 나뉘지 않는다.
 * 그런데 차량 비용은 {@link CostModel} 에 (차량, 거리, 시간, stop 수)를 다시 넣으면 나오고,
 * 나머지가 소프트 페널티다. 미배정 페널티는 총비용에서 라우트 비용의 합을 뺀 값이다.
 *
 * <p>계측을 위해 도메인 레코드에 필드를 더하지 않는다 — <strong>측정은 측정 쪽의 일</strong>이고,
 * 여기서 복원할 수 있는 값을 위해 운영 타입을 넓히면 그 필드는 운영에서 아무도 읽지 않는다.
 *
 * @param fixedKrw        차량 고정비 합
 * @param distanceKrw     거리비 합
 * @param timeKrw         시간비 합
 * @param softPenaltyKrw  소프트 룰 페널티 합 (보너스가 있으므로 음수일 수 있다)
 * @param unassignedKrw   미배정 페널티 합
 * @param vehiclesUsed    실제로 굴린 차량 수
 * @param unassignedOrders 미배정 주문 수
 * @param distanceM       총 이동 거리(m)
 */
public record CostBreakdown(long fixedKrw, long distanceKrw, long timeKrw, long softPenaltyKrw,
        long unassignedKrw, int vehiclesUsed, int unassignedOrders, long distanceM) {

    /**
     * 문제와 결과에서 복원한다.
     *
     * @param problem 계획 입력 (차량 비용 파라미터가 여기 있다)
     * @param result  계획 결과
     */
    public static CostBreakdown of(PlanningProblem problem, PlanResult result) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(result, "result");

        Map<VehicleId, VehicleSpec> byId = new LinkedHashMap<>();
        problem.vehicles().forEach(vehicle -> byId.put(vehicle.id(), vehicle));

        CostModel cost = problem.cost();
        long fixed = 0;
        long distance = 0;
        long time = 0;
        long routeTotal = 0;
        long meters = 0;

        for (PlannedRoute route : result.routes()) {
            VehicleSpec vehicle = byId.get(route.vehicle());
            if (vehicle == null) {
                throw new IllegalStateException("계획에 없는 차량입니다: " + route.vehicle());
            }
            fixed += vehicle.cost().fixed().krw();
            distance += Math.multiplyExact(vehicle.cost().perKm().krw(), (long) route.distanceM())
                    / 1000L;
            time += Math.multiplyExact(vehicle.cost().perMin().krw(), (long) route.durationS())
                    / 60L;
            routeTotal += route.cost().krw();
            meters += route.distanceM();
        }

        long vehicleCost = result.routes().stream()
                .mapToLong(route -> cost.routeCost(byId.get(route.vehicle()), route.distanceM(),
                        route.durationS(), route.stops().size()).krw())
                .sum();
        long soft = routeTotal - vehicleCost;
        long unassigned = result.totalCost().krw() - routeTotal;

        return new CostBreakdown(fixed, distance, time, soft, unassigned, result.routes().size(),
                result.unassigned().size(), meters);
    }

    /** 항의 합. {@code PlanResult.totalCost} 와 같아야 한다 — 테스트가 그것을 검사한다. */
    public long totalKrw() {
        return fixedKrw + distanceKrw + timeKrw + softPenaltyKrw + unassignedKrw;
    }
}
