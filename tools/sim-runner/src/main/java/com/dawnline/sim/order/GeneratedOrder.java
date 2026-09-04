package com.dawnline.sim.order;

import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/orders} 본문 (contracts/openapi/order-service.yaml 의 {@code PlaceOrderRequest}).
 *
 * <p>order-service 의 record 를 그대로 쓰지 않는다. 도구가 서비스 모듈에 의존하면 서비스를
 * 고칠 때마다 도구가 따라 깨지고, 무엇보다 <strong>계약을 테스트하지 못하게 된다</strong> —
 * 같은 클래스를 양쪽이 쓰면 직렬화 형태가 어긋날 수가 없어서, 어긋남을 잡을 기회도 없다.
 * 여기서는 <em>클라이언트가 보는 모양</em>을 따로 적고, 어긋나면 400 으로 드러나게 둔다.
 *
 * @param customerId  고객 id
 * @param serviceTier {@code DAWN} · {@code SAME_DAY} · {@code NEXT_DAY}
 * @param addressLine 주소 문자열
 * @param postalCode  5자리 우편번호
 * @param parcel      소포 제원
 * @param items       품목 (1건 이상)
 */
public record GeneratedOrder(
        UUID customerId,
        String serviceTier,
        String addressLine,
        String postalCode,
        Parcel parcel,
        List<Item> items) {

    public GeneratedOrder {
        items = List.copyOf(items);
    }

    /**
     * @param weightG      무게(g)
     * @param volumeCm3    부피(cm^3)
     * @param requiresCold 냉장 필요
     * @param hazmat       위험물
     */
    public record Parcel(int weightG, int volumeCm3, boolean requiresCold, boolean hazmat) {
    }

    /**
     * @param sku 상품 코드
     * @param qty 수량
     */
    public record Item(String sku, int qty) {
    }
}
