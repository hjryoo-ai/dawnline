package com.dawnline.order.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * {@code order_items} 행 (DESIGN.md §5.1).
 *
 * <p>{@link OrderEntity} 의 element collection 이다. 독립된 엔티티가 아닌 이유: 이 행은 주문 없이
 * 존재할 수 없고 자기만의 식별자도 없다 — {@code (order_id, line_no)} 가 PK 다. 애그리거트 경계
 * 안의 값이므로 JPA 에서도 그렇게 다룬다.
 *
 * <p>record 로 둔다. Hibernate 6.2+ 는 record 를 embeddable 로 지원하며 정규 생성자로 인스턴스를
 * 만든다 — 무인자 생성자를 요구하지 않는다. 실제로 그렇게 동작하는지는
 * {@code OrderPersistenceIT} 가 실물 PostgreSQL 왕복으로 확인한다.
 *
 * @param lineNo 주문 내 순번
 * @param sku    상품 코드
 * @param qty    수량
 */
@Embeddable
public record OrderItemEntity(
        @Column(name = "line_no", nullable = false) short lineNo,
        @Column(name = "sku", nullable = false, length = 32) String sku,
        @Column(name = "qty", nullable = false) int qty) {
}
