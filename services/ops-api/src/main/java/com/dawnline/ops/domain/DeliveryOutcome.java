package com.dawnline.ops.domain;

/**
 * {@code rm_orders.delivery_outcome} — tracking 의 결과 (DESIGN.md §5.5 「DDL 정정」).
 * {@code delivery.status} 만 쓴다.
 *
 * <p>「아직 결과 없음」은 값이 아니다 — 칸이 비어 있을 뿐이다. {@code order.placed} 가 행을
 * 만들면서 {@code NONE} 을 적으면 「배송되지 않았다」는, 아직 아무도 하지 않은 주장을 적는
 * 것이다(ADR-051 결정 2).
 *
 * <p>둘은 한 주문에 함께 오지 않는다(tracking 의 종료 상태). 그래도 순서를 두는 이유는
 * {@link Progress} 의 판정이 최댓값이 되게 하려는 것이다.
 */
public enum DeliveryOutcome {

    /** 배송 실패. */
    FAILED,

    /** 배송 완료. */
    COMPLETED
}
