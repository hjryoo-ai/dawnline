package com.dawnline.fulfillment.adapter.out.messaging;

import com.dawnline.common.TimeWindow;
import com.dawnline.fulfillment.application.port.in.PlacedOrderSnapshot;
import com.dawnline.fulfillment.domain.UnserviceableReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code fulfillment.planned.v1} 페이로드 (§4.3,
 * {@code contracts/events/fulfillment.planned.v1.schema.json}).
 *
 * <p>{@code order.placed} 스냅샷을 그대로 되싣고 판정 결과를 덧붙인다. 필드 목록을 스냅샷과
 * 공유하지 않고 여기 다시 적는 이유는 계약 README §4.7 과 같다 — <strong>페이로드는 파일마다
 * 자기 완결</strong>이고, 두 이벤트가 같은 타입을 공유하면 한쪽 스키마를 바꿀 때 다른 쪽이
 * 조용히 따라 바뀐다.
 *
 * @param outcome        {@code PLANNED} 또는 {@code UNSERVICEABLE}
 * @param orderId        주문 id
 * @param customerId     고객 id
 * @param serviceTier    티어
 * @param address        배송지
 * @param promisedWindow 지금 유효한 약속창
 * @param parcel         소포
 * @param items          품목
 * @param placedAt       접수 시각
 * @param fcId           선택된 FC ({@code PLANNED} 일 때만)
 * @param campId         캠프
 * @param zoneId         권역
 * @param waveId         웨이브
 * @param waveCutoffAt   웨이브 컷오프
 * @param promiseRevised 약속이 개정됐는가 (ADR-020). {@code PLANNED} 에서 필수다
 * @param reason         배차 불가 사유 ({@code UNSERVICEABLE} 일 때만)
 */
public record FulfillmentPlannedPayload(
        String outcome,
        UUID orderId,
        UUID customerId,
        String serviceTier,
        Address address,
        Window promisedWindow,
        Parcel parcel,
        List<Item> items,
        Instant placedAt,
        @Nullable UUID fcId,
        @Nullable UUID campId,
        @Nullable UUID zoneId,
        @Nullable UUID waveId,
        @Nullable Instant waveCutoffAt,
        @Nullable Boolean promiseRevised,
        @Nullable String reason) {

    /** {@code outbox_events.aggregate_type}. */
    public static final String AGGREGATE_TYPE = "order";

    /** 이벤트 타입 (§4.1). */
    public static final String EVENT_TYPE = "fulfillment.planned";

    /** 페이로드 스키마 major 버전. */
    public static final int SCHEMA_VERSION = 1;

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

    /**
     * 계획됨.
     *
     * @param snapshot     스냅샷
     * @param fcId         FC
     * @param campId       캠프
     * @param zoneId       권역
     * @param waveId       웨이브
     * @param waveCutoffAt 웨이브 컷오프
     * @param window       지금 유효한 약속창
     * @param revised      개정 여부
     */
    public static FulfillmentPlannedPayload planned(PlacedOrderSnapshot snapshot, UUID fcId, UUID campId,
            UUID zoneId, UUID waveId, Instant waveCutoffAt, TimeWindow window, boolean revised) {

        return new FulfillmentPlannedPayload("PLANNED", snapshot.orderId(), snapshot.customerId(),
                snapshot.serviceTier(), address(snapshot), new Window(window.start(), window.end()),
                parcel(snapshot), items(snapshot), snapshot.placedAt(),
                fcId, campId, zoneId, waveId, waveCutoffAt, revised, null);
    }

    /**
     * 배차 불가.
     *
     * <p>{@code promiseRevised} 를 넣지 않는다 — 배차되지 못한 주문에는 개정할 약속이 없다
     * (계약 README §4.5-1). 약속창은 접수 시점의 값 그대로 나간다.
     *
     * @param snapshot 스냅샷
     * @param reason   사유
     */
    public static FulfillmentPlannedPayload unserviceable(PlacedOrderSnapshot snapshot,
            UnserviceableReason reason) {

        TimeWindow window = snapshot.promisedWindow();
        return new FulfillmentPlannedPayload("UNSERVICEABLE", snapshot.orderId(), snapshot.customerId(),
                snapshot.serviceTier(), address(snapshot), new Window(window.start(), window.end()),
                parcel(snapshot), items(snapshot), snapshot.placedAt(),
                null, null, null, null, null, null, reason.name());
    }

    private static Address address(PlacedOrderSnapshot snapshot) {
        PlacedOrderSnapshot.Address source = snapshot.address();
        return new Address(source.line(), source.postalCode(), source.point().lat(),
                source.point().lng(), source.geohash7());
    }

    private static Parcel parcel(PlacedOrderSnapshot snapshot) {
        PlacedOrderSnapshot.Parcel source = snapshot.parcel();
        return new Parcel(source.weightG(), source.volumeCm3(), source.requiresCold(), source.hazmat());
    }

    private static List<Item> items(PlacedOrderSnapshot snapshot) {
        return snapshot.items().stream().map(item -> new Item(item.sku(), item.qty())).toList();
    }
}
