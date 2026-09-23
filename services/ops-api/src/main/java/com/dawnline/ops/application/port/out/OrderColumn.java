package com.dawnline.ops.application.port.out;

/**
 * {@code rm_orders} 에서 핸들러가 쓰는 칸 (DESIGN.md §5.5). 어댑터가 이름을 소문자로 바꿔 칸 이름으로 쓴다.
 *
 * <p>여기 없는 칸: {@code order_id}(키) · {@code on_time_*}(생성 칸 — 쓰는 사람이 없어서 순서를 탈 수
 * 없다) · {@code updated_at}(프로젝션의 기록). {@code ProjectionShuffleIT} 가 이 목록과 표의 칸을
 * 대조한다 — 칸이 늘었는데 여기 없으면 그 칸은 아무도 쓰지 않는 칸이다.
 */
public enum OrderColumn {
    CUSTOMER_ID,
    SERVICE_TIER,
    ORDER_STATUS,
    DELIVERY_OUTCOME,
    CAMP_ID,
    WAVE_ID,
    ROUTE_ID,
    PROMISED_END_ORIGINAL,
    PROMISED_END_REVISED,
    PLANNED_ARRIVAL,
    PLANNED_AS_OF,
    ETA_AT,
    ETA_AS_OF,
    DELIVERED_AT
}
