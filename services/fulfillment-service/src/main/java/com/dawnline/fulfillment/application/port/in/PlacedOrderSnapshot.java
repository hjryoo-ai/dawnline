package com.dawnline.fulfillment.application.port.in;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.TimeWindow;
import com.dawnline.fulfillment.domain.OrderLine;
import com.dawnline.fulfillment.domain.OrderToPlan;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code order.placed} 가 싣고 온 주문 스냅샷 (§4.3).
 *
 * <p>fulfillment 는 주문 테이블을 갖지 않는다(불변규칙 3 — 남의 서비스 DB 를 보지 않는다).
 * 판정에 필요한 것은 전부 이 스냅샷에 있고, {@code fulfillment.planned} 는 이것을 <strong>그대로
 * 되싣는다</strong>(§4.3 "order.placed 스냅샷 + fcId, campId, …") — 하류(dispatch)가 주문을
 * 다시 물어보지 않아도 되게 하기 위해서다.
 *
 * @param orderId        주문 id
 * @param customerId     고객 id
 * @param serviceTier    티어 이름. 계약의 enum 값이다
 * @param address        배송지
 * @param promisedWindow 접수 시점에 고객에게 한 약속
 * @param parcel         소포 속성
 * @param items          품목
 * @param placedAt       접수 시각
 * @param cutoffAt       이 주문이 실릴 웨이브의 컷오프 (ADR-020 — order-service 가 계산한 값)
 */
public record PlacedOrderSnapshot(
        UUID orderId,
        UUID customerId,
        String serviceTier,
        Address address,
        TimeWindow promisedWindow,
        Parcel parcel,
        List<Item> items,
        Instant placedAt,
        Instant cutoffAt) {

    public PlacedOrderSnapshot {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(serviceTier, "serviceTier");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(promisedWindow, "promisedWindow");
        Objects.requireNonNull(parcel, "parcel");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        Objects.requireNonNull(placedAt, "placedAt");
        // 저장 정밀도(마이크로초)로 자른다. libs/messaging 이 Clock 빈에 하는 것과 같은 이유이고,
        // 여기서는 더 날카롭다 — cutoffAt 은 웨이브의 **자연키**다.
        //
        // 자르지 않으면 나노초 값이 PostgreSQL TIMESTAMPTZ 에 들어가며 잘리고, 그 뒤
        //   * findByNaturalKey(나노초) 가 저장된 행을 못 찾아 INSERT 를 시도하고,
        //   * UNIQUE 에 걸려 재조회해도 여전히 못 찾아 예외가 되거나,
        //   * 운 좋게 찾아도 wave.cutoffAt() != snapshot.cutoffAt() 이라
        //     **모든 주문이 promiseRevised=true 로 나간다** — 거짓 약속 개정이다.
        //
        // 저장할 수 없는 정밀도의 키는 조회할 수도 없다. 그래서 받는 자리에서 자른다.
        // (Linux 의 Instant.now() 는 나노초, macOS 는 마이크로초라 이 결함은 CI 에서만 드러났다.)
        cutoffAt = Objects.requireNonNull(cutoffAt, "cutoffAt")
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    /**
     * 배송지.
     *
     * @param line       전체 주소. <strong>로그에 남기지 않는다</strong> (CLAUDE.md 로그 규칙)
     * @param postalCode 우편번호
     * @param point      좌표
     * @param geohash7   geohash7. 앞 5자가 권역 키다 (부록 C)
     */
    public record Address(String line, String postalCode, GeoPoint point, String geohash7) {

        public Address {
            Objects.requireNonNull(line, "line");
            Objects.requireNonNull(postalCode, "postalCode");
            Objects.requireNonNull(point, "point");
            Objects.requireNonNull(geohash7, "geohash7");
            if (geohash7.length() != 7) {
                throw new IllegalArgumentException("geohash7 은 7자여야 합니다: " + geohash7);
            }
        }

        /** 권역 조회 키 (§5.2 4단계). geohash5 셀 하나가 권역 하나다. */
        public String geohash5() {
            return geohash7.substring(0, 5);
        }
    }

    /**
     * 소포.
     *
     * @param weightG      무게(g)
     * @param volumeCm3    부피(cm³)
     * @param requiresCold 냉장 필요 (§5.2 2단계)
     * @param hazmat       위험물
     */
    public record Parcel(int weightG, int volumeCm3, boolean requiresCold, boolean hazmat) {
    }

    /**
     * 품목 한 줄.
     *
     * @param sku 상품 코드
     * @param qty 수량
     */
    public record Item(String sku, int qty) {

        public Item {
            Objects.requireNonNull(sku, "sku");
        }
    }

    /**
     * 판정 함수에 넘길 형태로 바꾼다.
     *
     * <p>{@link OrderToPlan} 은 <em>판정에 쓰는 것만</em> 담는다 — 주소도 고객도 없다. 그것이
     * 순수 함수의 입력 면적을 좁게 유지하는 방법이고, 좁을수록 테스트가 쉽다.
     */
    public OrderToPlan toOrderToPlan() {
        List<OrderLine> lines = items.stream().map(item -> new OrderLine(item.sku(), item.qty())).toList();
        return new OrderToPlan(orderId, com.dawnline.fulfillment.domain.ServiceTier.valueOf(serviceTier),
                parcel.requiresCold(), lines, cutoffAt);
    }
}
