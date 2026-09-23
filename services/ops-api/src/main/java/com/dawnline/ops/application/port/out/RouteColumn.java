package com.dawnline.ops.application.port.out;

/**
 * {@code rm_routes} 에서 핸들러가 쓰는 칸. {@code completed_count}·{@code failed_count} 는 없다 —
 * 집계라서 {@link RouteRows#recount} 가 쓴다(ADR-051 결정 4).
 */
public enum RouteColumn {
    PLAN_ID,
    CAMP_ID,
    VEHICLE_ID,
    DRIVER_ID,
    REVISION,
    STATUS,
    PLANNED_DEPARTURE,
    DEPARTED_AT,
    STOP_COUNT,
    AT_RISK,
    DISTANCE_M,
    COST_KRW
}
