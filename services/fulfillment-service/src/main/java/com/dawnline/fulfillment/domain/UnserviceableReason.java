package com.dawnline.fulfillment.domain;

/**
 * 배차할 수 없는 이유 (DESIGN.md §5.2 6단계, {@code contracts/events/README.md} §4.5).
 *
 * <p>{@code fulfillment.planned} 의 {@code reason} 으로 나가고, order-service 는 이 값을 주문
 * 실패 사유로 기록한다. 계약에서는 문자열이라 여기서 enum 으로 좁히는 것은 <em>이쪽의</em>
 * 선택이다 — 계약을 enum 으로 좁히면 값을 추가할 때 major 가 필요해진다(§4.7).
 */
public enum UnserviceableReason {

    /** 1단계 — 그 티어를 지원하는 FC 가 없다. */
    NO_FC_FOR_TIER,

    /** 2단계 — 냉장이 필요한데 {@code supports_cold} FC 가 없다. */
    NO_COLD_FC,

    /** 3단계 — 재고가 없다. */
    OUT_OF_STOCK,

    /** 4단계 — geohash5 → 권역 매핑 실패. 이 판정은 권역 조회를 하는 쪽이 내린다. */
    NO_ZONE_MATCH,

    /** 4~5단계 — 권역은 있으나 활성 캠프가 없다. */
    NO_ACTIVE_CAMP,

    /** 5~6단계 — 캠프 반경 50 km 안에 1~3단계를 통과한 FC 가 없다. */
    NO_ELIGIBLE_FC,

    /**
     * FC 선택 <strong>전</strong> — 컷오프가 24시간을 넘겼다 (ADR-020 후속 정정).
     *
     * <p>다른 사유는 "이 주소·상품을 지금 배송할 수 없다" 인데 이것만 <em>"이 이벤트가 너무 늦게
     * 왔다"</em> 이다. 20일 묵은 {@code order.placed} 가 DLQ replay 로 들어와도 다음 웨이브로
     * 밀려 오늘 날짜의 새 배송 약속이 나가지 않게 하는 방어다.
     */
    STALE_PLACED
}
