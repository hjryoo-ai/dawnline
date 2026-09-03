package com.dawnline.order.application.port.in;

/**
 * 배송 진행 이벤트를 상태 머신에 적용한 결과 (DESIGN.md §5.1, ADR-017).
 *
 * <p>세 갈래가 아니라 네 값인 이유: 거부는 <em>왜</em> 거부됐는지가 메트릭 태그가 되어야 하고,
 * 그 태그는 낮은 카디널리티여야 한다(§4.6). 사유를 문자열로 들고 다니는 대신 값으로 나눈다.
 *
 * <p>여기에 {@code EventRejectedException} 을 던지지 않고 값을 돌려주는 이유는 계층이다 —
 * 그 예외는 Kafka 소비 계약({@code libs/messaging})의 개념이고, 유스케이스는 "이 이벤트가
 * 상태를 바꿨는가" 만 말한다. 무엇으로 번역할지는 리스너 어댑터가 정한다.
 */
public enum OrderProgress {

    /** 상태가 바뀌었다. */
    APPLIED,

    /**
     * 이미 지나온 지점이라 무시했다 (§4.5 순서 뒤바뀜). 정상이며 DLQ 아님.
     * {@code dawnline_event_stale_total} 로 센다.
     */
    STALE,

    /**
     * 그런 주문이 없다. order-service 가 주문의 주인이므로 이것은 잘못된 이벤트다 —
     * 재시도해도 결과가 같으니 거부한다.
     */
    ORDER_NOT_FOUND,

    /**
     * 진행 축 밖의 전이다. 실질적으로 <strong>취소된 주문에 배송 이벤트가 온 경우</strong>이며,
     * 그것은 철 지난 중복이 아니라 실제로 잘못된 상황이다 — 취소된 주문의 소포가 차에 실려 있다는
     * 뜻이고 누군가 회수해야 한다 (ADR-017 §3).
     */
    TRANSITION_NOT_ALLOWED;

    /** 상태를 바꾸지 않았고 사람이 봐야 하는 결과인가. */
    public boolean isRejected() {
        return this == ORDER_NOT_FOUND || this == TRANSITION_NOT_ALLOWED;
    }
}
