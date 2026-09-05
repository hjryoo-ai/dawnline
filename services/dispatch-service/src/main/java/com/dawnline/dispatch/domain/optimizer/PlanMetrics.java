package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.error.ValidationException;

/**
 * 계획 한 번의 지표 (DESIGN.md §6.9 벤치마크 지표, §9.1 메트릭).
 *
 * <p>벤치마크 리포트의 한 행이 그대로 이 값이다 — 리포트와 운영 메트릭이 같은 숫자를 보게
 * 하려는 것이고, 두 곳에서 따로 계산하면 "리포트에서는 좋아졌는데 대시보드는 아니다" 가 된다.
 *
 * @param routeCount         만들어진 라우트 수
 * @param assignedOrders     배정된 주문 수
 * @param unassignedOrders   미배정 주문 수
 * @param vehiclesUsed       실제로 쓴 차량 수
 * @param totalDistanceM     총 이동 거리(m)
 * @param totalDurationS     총 소요 시간(초)
 * @param lateStops          약속창을 넘긴 stop 수
 * @param totalLateMinutes   지각 분의 합. 평균은 {@link #averageLateMinutes()} 가 낸다
 * @param planDurationMs     계획에 걸린 시간(ms)
 */
public record PlanMetrics(int routeCount, int assignedOrders, int unassignedOrders, int vehiclesUsed,
        long totalDistanceM, long totalDurationS, int lateStops, long totalLateMinutes,
        long planDurationMs) {

    public PlanMetrics {
        requireNonNegative(routeCount, "routeCount");
        requireNonNegative(assignedOrders, "assignedOrders");
        requireNonNegative(unassignedOrders, "unassignedOrders");
        requireNonNegative(vehiclesUsed, "vehiclesUsed");
        requireNonNegative(totalDistanceM, "totalDistanceM");
        requireNonNegative(totalDurationS, "totalDurationS");
        requireNonNegative(lateStops, "lateStops");
        requireNonNegative(totalLateMinutes, "totalLateMinutes");
        requireNonNegative(planDurationMs, "planDurationMs");
    }

    /**
     * 지각 stop 의 평균 지각 분. 지각이 없으면 0 이다.
     *
     * <p>분모가 전체 stop 이 아니라 <strong>지각한 stop</strong> 인 이유: "지각했을 때 얼마나
     * 늦었나" 를 보려는 값이다. 전체로 나누면 stop 을 늘리기만 해도 좋아 보인다.
     */
    public double averageLateMinutes() {
        return lateStops == 0 ? 0.0d : (double) totalLateMinutes / lateStops;
    }

    /** 미배정률 (§6.7 목표 ≤ 0.5%). 후보가 없으면 0 이다. */
    public double unassignedRatio() {
        int total = assignedOrders + unassignedOrders;
        return total == 0 ? 0.0d : (double) unassignedOrders / total;
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0L) {
            throw ValidationException.field(field, value, "지표는 음수일 수 없습니다");
        }
    }
}
