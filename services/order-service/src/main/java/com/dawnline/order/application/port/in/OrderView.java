package com.dawnline.order.application.port.in;

import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderStatus;
import com.dawnline.order.domain.ServiceTier;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 주문 상세 읽기 모델 (DESIGN.md §5.1 {@code GET /api/v1/orders/{id}}).
 *
 * <p>애그리거트를 그대로 돌려주지 않는다. {@link Order} 에는 상태 전이 메서드가 있고, 그것을 웹
 * 어댑터가 부를 수 있게 두면 트랜잭션 밖에서 상태가 바뀌는 경로가 열린다(불변규칙 6).
 *
 * <p>이 레코드의 모양이 곧 HTTP 응답 JSON 이다 — {@link OrderAccepted} 와 같은 선택이다.
 * 도메인 값 객체를 그대로 담지 않고 평평하게 펴는 이유는, 도메인이 바뀔 때 응답 모양이 따라
 * 바뀌지 않게 하기 위해서다.
 *
 * @param orderId       주문 id
 * @param customerId    고객 id
 * @param status        현재 상태
 * @param serviceTier   서비스 티어
 * @param address       배송지
 * @param promisedStart 약속 배송창 시작
 * @param promisedEnd   약속 배송창 종료
 * @param parcel        소포 제원
 * @param items         품목
 * @param placedAt      접수 시각
 * @param updatedAt     마지막 상태 변경 시각. 상태 타임라인의 최신 지점이다
 */
public record OrderView(
        UUID orderId,
        UUID customerId,
        OrderStatus status,
        ServiceTier serviceTier,
        AddressView address,
        Instant promisedStart,
        Instant promisedEnd,
        ParcelView parcel,
        List<ItemView> items,
        Instant placedAt,
        Instant updatedAt) {

    public OrderView {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(status, "status");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }

    /**
     * @param order 애그리거트
     */
    public static OrderView of(Order order) {
        Objects.requireNonNull(order, "order");
        return new OrderView(
                order.id(),
                order.customerId(),
                order.status(),
                order.serviceTier(),
                new AddressView(order.address().line(), order.address().postalCode(),
                        order.address().point().lat(), order.address().point().lng(),
                        order.address().geohash7()),
                order.promisedWindow().start(),
                order.promisedWindow().end(),
                new ParcelView(order.parcel().weightG(), order.parcel().volumeCm3(),
                        order.parcel().requiresCold(), order.parcel().hazmat()),
                order.items().stream()
                        .map(item -> new ItemView(item.lineNo(), item.sku(), item.qty()))
                        .toList(),
                order.placedAt(),
                order.updatedAt());
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
    public record AddressView(String line, String postalCode, double lat, double lng, String geohash7) {
    }

    /**
     * 소포 제원.
     *
     * @param weightG      무게(g)
     * @param volumeCm3    부피(cm^3)
     * @param requiresCold 냉장 필요
     * @param hazmat       위험물
     */
    public record ParcelView(int weightG, int volumeCm3, boolean requiresCold, boolean hazmat) {
    }

    /**
     * 품목. 이벤트와 달리 {@code lineNo} 를 포함한다 — 고객이 보는 화면에서는 주문서의 줄 번호가 의미를 갖는다.
     *
     * @param lineNo 줄 번호
     * @param sku    상품 코드
     * @param qty    수량
     */
    public record ItemView(short lineNo, String sku, int qty) {
    }
}
