package com.dawnline.ops.application.port.out;

/**
 * {@code rm_waves} 에서 핸들러가 쓰는 칸. {@code order_count} 는 없다 — 집계라서
 * {@link WaveRows#recountOrders} 가 쓴다(ADR-051 결정 4).
 */
public enum WaveColumn {
    CAMP_ID,
    SERVICE_TIER,
    CUTOFF_AT,
    STATUS,
    PLAN_ID,
    PLAN_DURATION_MS,
    TOTAL_COST_KRW,
    UNASSIGNED_COUNT,
    ROUTE_COUNT
}
