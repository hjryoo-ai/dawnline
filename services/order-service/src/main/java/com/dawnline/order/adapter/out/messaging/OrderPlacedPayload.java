package com.dawnline.order.adapter.out.messaging;

import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderItem;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code order.placed} v1 페이로드 (contracts/events/order.placed.v1.schema.json, DESIGN.md §4.3).
 *
 * <p>이 레코드가 <strong>계약</strong>이다. 컴포넌트 이름이 곧 JSON 키이므로 이름을 바꾸는 것은
 * major 변경이다. 같은 major 안에서는 필드 추가만 한다(§4.7, 불변규칙 8).
 * {@code OrderPlacedContractTest} 가 스키마로 실제 검증한다.
 *
 * <p>애그리거트를 그대로 직렬화하지 않는 이유: 도메인은 계속 바뀌고 계약은 그러면 안 된다.
 * 여기서 한 번 옮겨 적어야 도메인 필드를 고칠 때 계약 테스트가 깨진다 — 조용히 새 필드가 새어 나가거나
 * 필드가 사라지는 대신에.
 *
 * @param orderId        주문 id
 * @param customerId     고객 id
 * @param serviceTier    서비스 티어
 * @param address        배송지
 * @param promisedWindow 약속 배송창 (§2.2)
 * @param parcel         소포 제원
 * @param items          품목
 * @param placedAt       접수 시각
 */
public record OrderPlacedPayload(
        UUID orderId,
        UUID customerId,
        String serviceTier,
        Address address,
        Window promisedWindow,
        Parcel parcel,
        List<Item> items,
        Instant placedAt) {

    /** {@code eventType} (§4.1). */
    public static final String EVENT_TYPE = "order.placed";

    /** 페이로드 스키마 major 버전. */
    public static final int SCHEMA_VERSION = 1;

    /** {@code outbox_events.aggregate_type} VARCHAR(32). */
    public static final String AGGREGATE_TYPE = "order";

    /**
     * 애그리거트를 계약 모양으로 옮긴다.
     *
     * @param order 접수된 주문
     */
    public static OrderPlacedPayload of(Order order) {
        return new OrderPlacedPayload(
                order.id(),
                order.customerId(),
                order.serviceTier().name(),
                new Address(order.address().line(), order.address().postalCode(),
                        order.address().point().lat(), order.address().point().lng(),
                        order.address().geohash7()),
                new Window(order.promisedWindow().start(), order.promisedWindow().end()),
                new Parcel(order.parcel().weightG(), order.parcel().volumeCm3(),
                        order.parcel().requiresCold(), order.parcel().hazmat()),
                order.items().stream().map(Item::of).toList(),
                order.placedAt());
    }

    /**
     * 배송지.
     *
     * @param line       주소 문자열
     * @param postalCode 우편번호
     * @param lat        위도
     * @param lng        경도
     * @param geohash7   7자리 geohash
     */
    public record Address(String line, String postalCode, double lat, double lng, String geohash7) {
    }

    /**
     * 시간 구간.
     *
     * @param start 시작(포함)
     * @param end   종료(제외)
     */
    public record Window(Instant start, Instant end) {
    }

    /**
     * 소포 제원.
     *
     * @param weightG      무게(g)
     * @param volumeCm3    부피(cm^3)
     * @param requiresCold 냉장 필요
     * @param hazmat       위험물
     */
    public record Parcel(int weightG, int volumeCm3, boolean requiresCold, boolean hazmat) {
    }

    /**
     * 품목. {@code lineNo} 는 싣지 않는다 — 주문 안에서의 줄 번호는 order-service 의 내부 키이고,
     * 소비자(fulfillment)는 SKU 와 수량만 쓴다(§5.2 재고 가용 판정).
     *
     * @param sku 상품 코드
     * @param qty 수량
     */
    public record Item(String sku, int qty) {

        /**
         * @param item 도메인 품목
         */
        public static Item of(OrderItem item) {
            return new Item(item.sku(), item.qty());
        }
    }
}
