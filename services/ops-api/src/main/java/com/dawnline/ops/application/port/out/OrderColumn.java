package com.dawnline.ops.application.port.out;

/**
 * {@code rm_orders} 에서 핸들러가 쓰는 칸 (DESIGN.md §5.5). 어댑터가 이름을 소문자로 바꿔 칸 이름으로 쓴다.
 *
 * <p>여기 없는 칸: {@code order_id}(키) · {@code on_time_*}(생성 칸 — 쓰는 사람이 없어서 순서를 탈 수
 * 없다) · {@code updated_at}(프로젝션의 기록). {@code ProjectionShuffleIT} 가 이 목록과 표의 칸을
 * 대조한다 — 칸이 늘었는데 여기 없으면 그 칸은 아무도 쓰지 않는 칸이다.
 */
public enum OrderColumn {
    CUSTOMER_ID(ColumnFamily.ORDER),
    SERVICE_TIER(ColumnFamily.ORDER),
    ORDER_STATUS(ColumnFamily.ORDER),
    DELIVERY_OUTCOME(ColumnFamily.TRACKING),
    CAMP_ID(ColumnFamily.ORDER),
    WAVE_ID(ColumnFamily.ORDER),
    ROUTE_ID(ColumnFamily.PLAN),
    PROMISED_END_ORIGINAL(ColumnFamily.ORDER),
    PROMISED_END_REVISED(ColumnFamily.ORDER),
    PLANNED_ARRIVAL(ColumnFamily.PLAN),
    PLANNED_AS_OF(ColumnFamily.PLAN),
    ETA_AT(ColumnFamily.TRACKING),
    ETA_AS_OF(ColumnFamily.TRACKING),
    DELIVERED_AT(ColumnFamily.TRACKING);

    private final ColumnFamily family;

    OrderColumn(ColumnFamily family) {
        this.family = family;
    }

    /** 이 칸의 계열 — 무엇이 판정 키인가(§5.5). */
    public ColumnFamily family() {
        return family;
    }
}
