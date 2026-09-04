package com.dawnline.order.adapter.in.web;

import com.dawnline.order.application.port.in.PlaceOrderCommand;
import com.dawnline.order.domain.OrderItem;
import com.dawnline.order.domain.Parcel;
import com.dawnline.order.domain.ServiceTier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/orders} 요청 본문 (DESIGN.md §5.1).
 *
 * <p>여기에 <strong>없는 것</strong>이 이 API 의 설계다. 주문 id·접수 시각·약속 배송창·좌표는
 * 서버가 정한다. 특히 약속 배송창은 §2.2 표에서 나오며 클라이언트가 지정할 수 없다 —
 * 배송 SLA 를 호출자가 정하면 그것은 약속이 아니다.
 *
 * <p>{@code lineNo} 도 없다. 품목의 줄 번호는 배열 순서로 정해진다(1부터). 클라이언트가 번호를
 * 매기게 하면 중복·건너뜀을 검증하는 규칙이 API 표면에 하나 더 생기는데, 얻는 것이 없다.
 *
 * <p>제약은 도메인 값 객체와 DB 컬럼에서 온 것들이다. Bean Validation 으로 앞에서 거르는 이유는
 * 오류 응답의 모양 때문이다 — 도메인 예외는 필드 하나를 가리키지만, 여기서는 <em>어긋난 필드
 * 전부</em>를 한 번에 돌려줄 수 있다.
 *
 * @param customerId  고객 id. 무인증 API 이므로 클라이언트 주장값이다 (§10)
 * @param serviceTier 서비스 티어
 * @param addressLine 배송지 주소 문자열
 * @param postalCode  대한민국 5자리 우편번호
 * @param parcel      소포 제원
 * @param items       품목 (1건 이상)
 */
public record PlaceOrderRequest(
        @NotNull UUID customerId,
        @NotNull ServiceTier serviceTier,
        @NotBlank @Size(max = 200) String addressLine,
        @NotNull @Pattern(regexp = "^[0-9]{5}$", message = "5자리 숫자여야 합니다") String postalCode,
        @NotNull @Valid ParcelRequest parcel,
        @NotEmpty @Size(max = 200) List<@Valid @NotNull ItemRequest> items) {

    /**
     * 명령으로 옮긴다. 줄 번호는 여기서 1부터 매긴다.
     *
     * @param idempotencyKey {@code Idempotency-Key} 헤더 값
     */
    public PlaceOrderCommand toCommand(String idempotencyKey) {
        List<OrderItem> orderItems = new java.util.ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            ItemRequest item = items.get(index);
            orderItems.add(new OrderItem((short) (index + 1), item.sku(), item.qty()));
        }
        return new PlaceOrderCommand(idempotencyKey, customerId, serviceTier, addressLine, postalCode,
                new Parcel(parcel.weightG(), parcel.volumeCm3(), parcel.requiresCold(), parcel.hazmat()),
                orderItems);
    }

    /**
     * 소포 제원. {@code requiresCold}·{@code hazmat} 은 {@code Boolean} 이 아니라 {@code boolean} 이라
     * 누락되면 {@code false} 가 된다 — 냉장 사고가 그렇게 난다. 그래서 {@link NotNull} 을 붙여
     * "보내지 않음" 과 "false" 를 구분한다.
     *
     * @param weightG      무게(g)
     * @param volumeCm3    부피(cm^3)
     * @param requiresCold 냉장 필요 여부
     * @param hazmat       위험물 여부
     */
    public record ParcelRequest(
            @NotNull @Min(0) @Max(1_000_000) Integer weightG,
            @NotNull @Min(0) @Max(1_000_000) Integer volumeCm3,
            @NotNull Boolean requiresCold,
            @NotNull Boolean hazmat) {
    }

    /**
     * 품목.
     *
     * @param sku 상품 코드
     * @param qty 수량
     */
    public record ItemRequest(
            @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String sku,
            @NotNull @Min(1) Integer qty) {
    }
}
