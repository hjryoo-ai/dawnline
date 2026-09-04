package com.dawnline.fulfillment.domain;

import java.util.Objects;

/**
 * 주문 품목 한 줄. 재고 확인(§5.2 3단계)에만 쓴다.
 *
 * @param sku 상품 코드
 * @param qty 수량
 */
public record OrderLine(String sku, int qty) {

    public OrderLine {
        Objects.requireNonNull(sku, "sku");
        if (sku.isBlank()) {
            throw new IllegalArgumentException("sku 는 비어 있을 수 없습니다");
        }
        if (qty < 1) {
            throw new IllegalArgumentException("qty 는 1 이상이어야 합니다: " + qty);
        }
    }
}
