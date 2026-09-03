package com.dawnline.order.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Ids;
import com.dawnline.order.application.port.in.PlaceOrderCommand;
import com.dawnline.order.domain.OrderItem;
import com.dawnline.order.domain.ServiceTier;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 요청 → 명령 변환과 Bean Validation 제약 (DESIGN.md §5.1).
 *
 * <p>제약은 실물 {@link Validator} 로 확인한다. 어노테이션이 붙어 있다는 것과 그것이 실제로
 * 검사된다는 것은 다르다 — 예를 들어 {@code boolean} 필드에 {@code @NotNull} 을 붙이면 아무 일도
 * 일어나지 않는다(누락이 {@code false} 로 채워진다).
 */
@DisplayName("PlaceOrderRequest — 변환과 검증")
class PlaceOrderRequestTest {

    private static final UUID CUSTOMER = Ids.newId();
    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static PlaceOrderRequest request(List<PlaceOrderRequest.ItemRequest> items) {
        return new PlaceOrderRequest(CUSTOMER, ServiceTier.DAWN, "서울 강남구 테헤란로 1", "06236",
                new PlaceOrderRequest.ParcelRequest(1200, 8000, false, false), items);
    }

    private static Set<String> violatedFields(PlaceOrderRequest request) {
        return VALIDATOR.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    @Test
    void 줄_번호는_배열_순서로_1부터_매긴다() {
        // 클라이언트가 번호를 매기게 하면 중복·건너뜀을 검증하는 규칙이 API 표면에 하나 더 생긴다.
        PlaceOrderCommand command = request(List.of(
                new PlaceOrderRequest.ItemRequest("SKU-A", 1),
                new PlaceOrderRequest.ItemRequest("SKU-B", 2),
                new PlaceOrderRequest.ItemRequest("SKU-C", 3))).toCommand("key-1");

        assertThat(command.items()).containsExactly(
                new OrderItem((short) 1, "SKU-A", 1),
                new OrderItem((short) 2, "SKU-B", 2),
                new OrderItem((short) 3, "SKU-C", 3));
    }

    @Test
    void 나머지_필드가_그대로_옮겨진다() {
        PlaceOrderCommand command = request(List.of(new PlaceOrderRequest.ItemRequest("SKU-A", 1)))
                .toCommand("key-1");

        assertThat(command.idempotencyKey()).isEqualTo("key-1");
        assertThat(command.customerId()).isEqualTo(CUSTOMER);
        assertThat(command.serviceTier()).isEqualTo(ServiceTier.DAWN);
        assertThat(command.addressLine()).isEqualTo("서울 강남구 테헤란로 1");
        assertThat(command.postalCode()).isEqualTo("06236");
        assertThat(command.parcel().weightG()).isEqualTo(1200);
        assertThat(command.parcel().volumeCm3()).isEqualTo(8000);
    }

    @Test
    void 올바른_요청에는_위반이_없다() {
        assertThat(violatedFields(request(List.of(new PlaceOrderRequest.ItemRequest("SKU-A", 1))))).isEmpty();
    }

    @Test
    void 우편번호는_5자리_숫자여야_한다() {
        PlaceOrderRequest broken = new PlaceOrderRequest(CUSTOMER, ServiceTier.DAWN, "주소", "062",
                new PlaceOrderRequest.ParcelRequest(1, 1, false, false),
                List.of(new PlaceOrderRequest.ItemRequest("SKU-A", 1)));

        assertThat(violatedFields(broken)).containsExactly("postalCode");
    }

    @Test
    void 품목이_비면_거부한다() {
        assertThat(violatedFields(request(List.of()))).contains("items");
    }

    @Test
    void 냉장_위험물_누락을_false_와_구분한다() {
        // boolean 이면 누락이 조용히 false 가 된다. 냉장 사고가 그렇게 난다.
        PlaceOrderRequest missingFlags = new PlaceOrderRequest(CUSTOMER, ServiceTier.DAWN, "주소", "06236",
                new PlaceOrderRequest.ParcelRequest(1, 1, null, null),
                List.of(new PlaceOrderRequest.ItemRequest("SKU-A", 1)));

        assertThat(violatedFields(missingFlags))
                .containsExactlyInAnyOrder("parcel.requiresCold", "parcel.hazmat");
    }

    @Test
    void 품목_안의_제약도_검사된다() {
        // 목록 원소에 @Valid 를 빠뜨리면 안쪽 제약이 조용히 무시된다.
        PlaceOrderRequest brokenItem = request(List.of(new PlaceOrderRequest.ItemRequest("SKU A!", 0)));

        assertThat(violatedFields(brokenItem))
                .containsExactlyInAnyOrder("items[0].sku", "items[0].qty");
    }

    @Test
    void 여러_필드가_어긋나면_모두_보고한다() {
        PlaceOrderRequest broken = new PlaceOrderRequest(null, null, "  ", "abc",
                new PlaceOrderRequest.ParcelRequest(-1, 1, false, false), List.of());

        assertThat(violatedFields(broken))
                .contains("customerId", "serviceTier", "addressLine", "postalCode", "parcel.weightG", "items");
    }
}
