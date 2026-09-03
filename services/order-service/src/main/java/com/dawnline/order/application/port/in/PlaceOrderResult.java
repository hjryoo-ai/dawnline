package com.dawnline.order.application.port.in;

import java.util.Objects;

/**
 * 접수 결과 (DESIGN.md §5.1 — 201 또는 중복 시 200).
 *
 * <p>HTTP 상태 코드를 여기 두지 않는다. {@code application} 은 HTTP 를 모른다 — 어댑터가
 * {@code replayed} 를 보고 201/200 을 고른다.
 *
 * @param order    접수 응답. 재생이면 그때 저장된 값 그대로다
 * @param replayed 같은 멱등 키의 재요청이라 저장된 응답을 돌려준 것인가
 */
public record PlaceOrderResult(OrderAccepted order, boolean replayed) {

    public PlaceOrderResult {
        Objects.requireNonNull(order, "order");
    }
}
