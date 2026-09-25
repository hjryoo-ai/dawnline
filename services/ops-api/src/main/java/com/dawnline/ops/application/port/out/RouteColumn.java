package com.dawnline.ops.application.port.out;

/**
 * {@code rm_routes} 에서 핸들러가 쓰는 칸. {@code completed_count}·{@code failed_count}·{@code completed_at} 은 없다 —
 * 집계라서 {@link RouteRows#recount} 가 쓴다(ADR-051 결정 4 · ADR-061).
 */
public enum RouteColumn {
    PLAN_ID(ColumnFamily.PLAN),
    CAMP_ID(ColumnFamily.KEY),
    VEHICLE_ID(ColumnFamily.PLAN),
    DRIVER_ID(ColumnFamily.PLAN),
    REVISION(ColumnFamily.PLAN),
    STATUS(ColumnFamily.AXIS),
    PLANNED_DEPARTURE(ColumnFamily.PLAN),
    DEPARTED_AT(ColumnFamily.TRACKING),
    STOP_COUNT(ColumnFamily.PLAN),
    AT_RISK(ColumnFamily.TRACKING),
    DISTANCE_M(ColumnFamily.PLAN),
    COST_KRW(ColumnFamily.PLAN);

    private final ColumnFamily family;

    RouteColumn(ColumnFamily family) {
        this.family = family;
    }

    /** 이 칸의 계열 — 무엇이 판정 키인가(§5.5). */
    public ColumnFamily family() {
        return family;
    }
}
