package com.dawnline.order.domain;

import com.dawnline.common.error.ValidationException;
import java.util.Objects;

/**
 * 주문 품목 (DESIGN.md §5.1 {@code order_items}).
 *
 * <p>애그리거트 {@link Order} 안에서만 존재한다. {@code lineNo} 는 주문 안에서의 순번이며
 * {@code (order_id, line_no)} 가 기본키다 — SKU 를 키로 쓰지 않는 이유는 같은 SKU 를 다른 조건으로
 * 두 줄에 담는 주문이 실제로 있기 때문이다.
 *
 * @param lineNo 주문 내 순번 (1부터)
 * @param sku    상품 코드
 * @param qty    수량
 */
public record OrderItem(short lineNo, String sku, int qty) {

    private static final int MAX_SKU_LENGTH = 32;

    public OrderItem {
        if (lineNo < 1) {
            throw ValidationException.field("lineNo", lineNo, "1 이상이어야 합니다");
        }
        Objects.requireNonNull(sku, "sku");
        sku = sku.strip();
        if (sku.isEmpty()) {
            throw ValidationException.field("sku", sku, "비어 있을 수 없습니다");
        }
        if (sku.length() > MAX_SKU_LENGTH) {
            throw ValidationException.field("sku", sku.length(), MAX_SKU_LENGTH + "자 이하여야 합니다");
        }
        if (qty <= 0) {
            // DDL 의 CHECK (qty > 0) 와 같은 규칙이다. DB 까지 가서 알기 전에 여기서 막는다.
            throw ValidationException.field("qty", qty, "0보다 커야 합니다");
        }
    }
}
