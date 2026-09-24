package com.dawnline.ops.application.port.out;

/**
 * {@code rm_waves} 에서 핸들러가 쓰는 칸. {@code order_count} 는 없다 — 집계라서
 * {@link WaveRows#recountOrders} 가 쓴다(ADR-051 결정 4).
 */
public enum WaveColumn {
    CAMP_ID(ColumnFamily.KEY),
    SERVICE_TIER(ColumnFamily.KEY),
    CUTOFF_AT(ColumnFamily.KEY),
    /** {@code wave.closed} 의 창고 좌표 — 웨이브의 불변 속성(V3). */
    DEPOT_LAT(ColumnFamily.KEY),
    DEPOT_LNG(ColumnFamily.KEY),
    STATUS(ColumnFamily.AXIS),
    PLAN_ID(ColumnFamily.PLAN),
    PLAN_DURATION_MS(ColumnFamily.PLAN),
    TOTAL_COST_KRW(ColumnFamily.PLAN),
    UNASSIGNED_COUNT(ColumnFamily.PLAN),
    ROUTE_COUNT(ColumnFamily.PLAN);

    private final ColumnFamily family;

    WaveColumn(ColumnFamily family) {
        this.family = family;
    }

    /** 이 칸의 계열 — 무엇이 판정 키인가(§5.5). */
    public ColumnFamily family() {
        return family;
    }
}
