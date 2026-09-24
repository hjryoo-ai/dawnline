package com.dawnline.tracking.domain;

/**
 * 기사 스캔 종류 (DESIGN.md §5.4 — {@code POST /api/v1/routes/{id}/stops/{seq}/events}).
 *
 * <p>스캔은 <strong>사건</strong>이고 상태는 그 사건의 결과다. 둘을 같은 이름으로 쓰지 않는 이유는
 * {@code DEPARTED_CAMP} 하나로 충분하다 — 그 사건이 만드는 상태는 {@code OUT_FOR_DELIVERY} 다.
 *
 * <p>{@code delivery.status.v1} 이 싣는 값은 셋({@code ARRIVED}·{@code COMPLETED}·{@code FAILED})
 * 이다. {@code DEPARTED_CAMP} 는 그 셋에 없다 — stop 의 사건이 아니라 <strong>라우트의 사건</strong>
 * 이라 stop 수만큼 반복해 말하는 꼴이 되기 때문이다. 대신 라우트에 하나,
 * {@code delivery.route-departed.v1} 로 나간다(ADR-050).
 */
public enum ScanType {

    /** 캠프 출발. */
    DEPARTED_CAMP(ShipmentStatus.OUT_FOR_DELIVERY),

    /** 배송지 도착. */
    ARRIVED(ShipmentStatus.ARRIVED),

    /** 전달 완료. */
    COMPLETED(ShipmentStatus.COMPLETED),

    /** 전달 실패. */
    FAILED(ShipmentStatus.FAILED);

    private final ShipmentStatus target;

    ScanType(ShipmentStatus target) {
        this.target = target;
    }

    /**
     * 이 스캔이 만드는 상태.
     *
     * @return 목표 상태
     */
    public ShipmentStatus targetStatus() {
        return target;
    }

    /**
     * {@code delivery.status.v1} 의 {@code status} 값인가 (계약의 enum 셋).
     *
     * <p>이름을 {@code isPublished} 에서 좁혔다(ADR-050 결과, 2026-09-24). 그 이름은 「발행되는가」로
     * 읽히는데 {@code DEPARTED_CAMP} 도 이제 발행된다 — 다만 이 토픽이 아니라
     * {@code delivery.route-departed} 로. 고치지 않으면 다음 사람은 출발이 아무 데도 안 나간다고 읽는다.
     *
     * @return {@code delivery.status} 로 나가는 스캔이면 {@code true}
     */
    public boolean isDeliveryStatus() {
        return this != DEPARTED_CAMP;
    }
}
