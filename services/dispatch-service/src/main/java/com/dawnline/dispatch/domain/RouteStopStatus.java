package com.dawnline.dispatch.domain;

/**
 * {@code route_stops.status} 의 값 (DESIGN.md §5.3).
 *
 * <p>진행 축은 {@link #PLANNED}(0) → {@link #ARRIVED}(1) → {@link #COMPLETED}·{@link #FAILED}(2)
 * 이고 {@link #CANCELLED} 는 <strong>축 밖</strong>이다 — order-service 의 {@code orders.status}
 * 와 같은 모양이다(§5.1). 축 밖에 두는 이유도 같다: 취소는 잘못된 상황이 아니라 설계된 경합
 * 창의 산물이라, 축 안에 넣어 stale 로 조용히 흡수되면 그 창의 크기를 볼 수 없다.
 *
 * <p>뒤의 셋({@code ARRIVED}·{@code COMPLETED}·{@code FAILED})은 {@code delivery.status} 소비가
 * 옮긴다([ADR-047](../../../../../../../../docs/adr/ADR-047-delivery-status-is-a-fact-not-a-revision.md)).
 */
public enum RouteStopStatus {

    /** 계획됨. 기사가 아직 닿지 않았다. */
    PLANNED(0),

    /** 취소됨 — <strong>축 밖</strong>이므로 단계가 없다. */
    CANCELLED(-1),

    /** 기사가 지점에 닿았다. */
    ARRIVED(1),

    /** 배송 완료. */
    COMPLETED(2),

    /** 배송 실패(부재·수취 거부 등). <strong>종결</strong>이다 — §6.8 이 다시 배정하지 않는다. */
    FAILED(2);

    private final int stage;

    RouteStopStatus(int stage) {
        this.stage = stage;
    }

    /**
     * 진행 축에서의 단계. {@link #CANCELLED} 는 축 밖이라 {@code -1} 이고, 이 값으로 비교되는
     * 일이 없도록 {@link #onAxis()} 를 먼저 묻는다.
     *
     * @return 단계
     */
    public int stage() {
        return stage;
    }

    /**
     * 진행 축 위에 있는가.
     *
     * @return 축 위면 참
     */
    public boolean onAxis() {
        return this != CANCELLED;
    }

    /**
     * 기사가 이미 그 지점에 닿았는가 (§6.10 넷째 분기 — 닿았으면 취소는 거부된다).
     *
     * <p>{@link #FAILED} 도 참이다. 실패는 <em>가서</em> 실패한 것이라 취소가 되돌릴 것이 없다.
     *
     * @return 닿았으면 참
     */
    public boolean visited() {
        return stage >= ARRIVED.stage;
    }

    /**
     * 더 옮길 곳이 없는가 — §6.8 의 「미완료 stop 만」이 읽는 판정이다.
     *
     * @return 종결이면 참
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
