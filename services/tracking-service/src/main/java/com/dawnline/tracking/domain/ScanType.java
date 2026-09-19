package com.dawnline.tracking.domain;

/**
 * 기사 스캔 종류 (DESIGN.md §5.4 — {@code POST /api/v1/routes/{id}/stops/{seq}/events}).
 *
 * <p>스캔은 <strong>사건</strong>이고 상태는 그 사건의 결과다. 둘을 같은 이름으로 쓰지 않는 이유는
 * {@code DEPARTED_CAMP} 하나로 충분하다 — 그 사건이 만드는 상태는 {@code OUT_FOR_DELIVERY} 다.
 *
 * <p>{@code delivery.status.v1} 이 싣는 값은 셋({@code ARRIVED}·{@code COMPLETED}·{@code FAILED})
 * 이다. {@code DEPARTED_CAMP} 는 발행하지 않는다 — order-service 의 상태 머신에 대응하는 상태가
 * 없고, 라우트 진행은 ops 가 {@code shipments} 로 본다.
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
     * {@code delivery.status.v1} 로 발행되는 스캔인가 (계약의 {@code status} enum 셋).
     *
     * @return 발행 대상이면 {@code true}
     */
    public boolean isPublished() {
        return this != DEPARTED_CAMP;
    }
}
