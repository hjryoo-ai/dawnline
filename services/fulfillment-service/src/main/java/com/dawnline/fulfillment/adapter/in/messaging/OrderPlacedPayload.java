package com.dawnline.fulfillment.adapter.in.messaging;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.TimeWindow;
import com.dawnline.fulfillment.application.port.in.PlacedOrderSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code order.placed.v1} 페이로드 (§4.3, {@code contracts/events/order.placed.v1.schema.json}).
 *
 * <p>order-service 의 같은 이름 record 와 <strong>의도적인 중복</strong>이다 — 서비스 간 소스
 * 의존 금지(불변규칙 3)이고, 공유되는 진실은 계약 파일이다. 계약 테스트가 예시 이벤트로 이
 * record 를 역직렬화해 어긋남을 잡는다.
 *
 * @param orderId        주문 id
 * @param customerId     고객 id
 * @param serviceTier    티어
 * @param address        배송지
 * @param promisedWindow 접수 시점의 약속
 * @param parcel         소포
 * @param items          품목
 * @param placedAt       접수 시각
 * @param cutoffAt       웨이브 컷오프 (ADR-020)
 */
public record OrderPlacedPayload(
        UUID orderId,
        UUID customerId,
        String serviceTier,
        Address address,
        Window promisedWindow,
        Parcel parcel,
        List<Item> items,
        Instant placedAt,
        Instant cutoffAt) {

    /** 계약의 {@code deliveryAddress}. */
    public record Address(String line, String postalCode, double lat, double lng, String geohash7) {
    }

    /** 계약의 {@code timeWindow}. */
    public record Window(Instant start, Instant end) {
    }

    /** 계약의 {@code parcel}. */
    public record Parcel(int weightG, int volumeCm3, boolean requiresCold, boolean hazmat) {
    }

    /** 계약의 {@code orderItem}. */
    public record Item(String sku, int qty) {
    }

    /** 유스케이스에 넘길 스냅샷으로 바꾼다. */
    public PlacedOrderSnapshot toSnapshot() {
        return new PlacedOrderSnapshot(
                orderId,
                customerId,
                serviceTier,
                new PlacedOrderSnapshot.Address(address.line(), address.postalCode(),
                        new GeoPoint(address.lat(), address.lng()), address.geohash7()),
                new TimeWindow(promisedWindow.start(), promisedWindow.end()),
                new PlacedOrderSnapshot.Parcel(parcel.weightG(), parcel.volumeCm3(),
                        parcel.requiresCold(), parcel.hazmat()),
                items.stream().map(item -> new PlacedOrderSnapshot.Item(item.sku(), item.qty())).toList(),
                placedAt,
                cutoffAt);
    }
}
