package com.dawnline.order.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.Ids;
import com.dawnline.common.error.ValidationException;
import com.dawnline.order.domain.OrderItem;
import com.dawnline.order.domain.Parcel;
import com.dawnline.order.domain.ServiceTier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 요청 지문 (DESIGN.md §5.1 2단계, ADR-018 §5).
 *
 * <p>여기서 지키려는 성질은 하나다 — <strong>의미가 다른 두 요청은 지문이 다르다.</strong>
 * 이것이 깨지면 두 번째 요청이 첫 번째의 응답을 받고, 그 사실은 아무 로그에도 남지 않는다.
 */
@DisplayName("PlaceOrderCommand — 요청 지문")
class PlaceOrderCommandTest {

    private static final UUID CUSTOMER = Ids.newId();

    /**
     * 표준형이 덮어야 하는 필드 목록. 레코드에 컴포넌트를 더하면 이 테스트가 먼저 깨지고,
     * 그때 {@link PlaceOrderCommand#canonicalForm()} 도 함께 고치게 된다.
     */
    private static final List<String> COMMAND_COMPONENTS = List.of(
            "idempotencyKey", "customerId", "serviceTier", "addressLine", "postalCode", "parcel", "items");

    private static final List<String> PARCEL_COMPONENTS =
            List.of("weightG", "volumeCm3", "requiresCold", "hazmat");

    private static final List<String> ITEM_COMPONENTS = List.of("lineNo", "sku", "qty");

    private static PlaceOrderCommand base() {
        return new PlaceOrderCommand("idem-key-1", CUSTOMER, ServiceTier.DAWN,
                "서울 강남구 테헤란로 1", "06236",
                new Parcel(1200, 8000, false, false),
                List.of(new OrderItem((short) 1, "SKU-1001", 2)));
    }

    private static List<String> componentNamesOf(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private static void assertFingerprintDiffers(String label, PlaceOrderCommand variant) {
        assertThat(variant.fingerprint())
                .as("%s 만 다른 요청은 지문도 달라야 한다", label)
                .isNotEqualTo(base().fingerprint());
    }

    @Test
    void 표준형이_덮는_필드와_레코드_컴포넌트가_일치한다() {
        // 컴포넌트를 더했는데 canonicalForm 에 넣는 것을 잊으면, 그 필드만 다른 두 요청이 같은 지문을
        // 갖는다 — 두 번째 요청이 첫 번째의 응답을 받는다. 아래 목록이 그 실수를 막는 유일한 자동 장치다.
        assertThat(componentNamesOf(PlaceOrderCommand.class))
                .as("컴포넌트를 더했다면 canonicalForm() 과 이 목록을 함께 고쳐야 한다")
                .containsExactlyElementsOf(COMMAND_COMPONENTS);
        assertThat(componentNamesOf(Parcel.class)).containsExactlyElementsOf(PARCEL_COMPONENTS);
        assertThat(componentNamesOf(OrderItem.class)).containsExactlyElementsOf(ITEM_COMPONENTS);
    }

    @Test
    void 같은_요청은_같은_지문이다() {
        assertThat(base().fingerprint()).isEqualTo(base().fingerprint());
    }

    @Test
    void 지문은_소문자_16진수_64자다() {
        // idempotency_keys.request_hash 는 CHAR(64) 다. 길이가 어긋나면 DB 가 조용히 잘라 낸다.
        assertThat(base().fingerprint()).hasSize(64).matches("^[0-9a-f]{64}$");
    }

    @Test
    void 최상위_필드가_하나라도_다르면_지문이_다르다() {
        assertFingerprintDiffers("idempotencyKey", new PlaceOrderCommand("idem-key-2", CUSTOMER,
                ServiceTier.DAWN, "서울 강남구 테헤란로 1", "06236",
                new Parcel(1200, 8000, false, false), List.of(new OrderItem((short) 1, "SKU-1001", 2))));
        assertFingerprintDiffers("customerId", new PlaceOrderCommand("idem-key-1", Ids.newId(),
                ServiceTier.DAWN, "서울 강남구 테헤란로 1", "06236",
                new Parcel(1200, 8000, false, false), List.of(new OrderItem((short) 1, "SKU-1001", 2))));
        assertFingerprintDiffers("serviceTier", new PlaceOrderCommand("idem-key-1", CUSTOMER,
                ServiceTier.SAME_DAY, "서울 강남구 테헤란로 1", "06236",
                new Parcel(1200, 8000, false, false), List.of(new OrderItem((short) 1, "SKU-1001", 2))));
        assertFingerprintDiffers("addressLine", new PlaceOrderCommand("idem-key-1", CUSTOMER,
                ServiceTier.DAWN, "서울 강남구 테헤란로 2", "06236",
                new Parcel(1200, 8000, false, false), List.of(new OrderItem((short) 1, "SKU-1001", 2))));
        assertFingerprintDiffers("postalCode", new PlaceOrderCommand("idem-key-1", CUSTOMER,
                ServiceTier.DAWN, "서울 강남구 테헤란로 1", "06237",
                new Parcel(1200, 8000, false, false), List.of(new OrderItem((short) 1, "SKU-1001", 2))));
    }

    @Test
    void 소포_제원이_하나라도_다르면_지문이_다르다() {
        // 냉장·위험물은 boolean 두 개가 나란히 있어 특히 조용하다. 지문이 그것을 구분해야
        // "같은 주소·같은 품목인데 냉장만 켠" 재요청이 이전 응답을 받지 않는다.
        Stream.of(new Parcel(1201, 8000, false, false),
                        new Parcel(1200, 8001, false, false),
                        new Parcel(1200, 8000, true, false),
                        new Parcel(1200, 8000, false, true))
                .forEach(parcel -> assertFingerprintDiffers("parcel=" + parcel,
                        new PlaceOrderCommand("idem-key-1", CUSTOMER, ServiceTier.DAWN,
                                "서울 강남구 테헤란로 1", "06236", parcel,
                                List.of(new OrderItem((short) 1, "SKU-1001", 2)))));
    }

    @Test
    void 품목이_하나라도_다르면_지문이_다르다() {
        Stream.of(List.of(new OrderItem((short) 2, "SKU-1001", 2)),
                        List.of(new OrderItem((short) 1, "SKU-1002", 2)),
                        List.of(new OrderItem((short) 1, "SKU-1001", 3)),
                        List.of(new OrderItem((short) 1, "SKU-1001", 2), new OrderItem((short) 2, "SKU-2", 1)))
                .forEach(items -> assertFingerprintDiffers("items=" + items,
                        new PlaceOrderCommand("idem-key-1", CUSTOMER, ServiceTier.DAWN,
                                "서울 강남구 테헤란로 1", "06236",
                                new Parcel(1200, 8000, false, false), items)));
    }

    @Test
    void 품목_순서가_바뀌면_다른_요청이다() {
        // 줄 번호가 있으므로 순서는 의미를 갖는다. 정렬해서 지우면 (1,A)(2,B) 와 (1,B)(2,A) 가 같아진다.
        PlaceOrderCommand ab = new PlaceOrderCommand("k", CUSTOMER, ServiceTier.DAWN, "주소", "06236",
                new Parcel(1, 1, false, false),
                List.of(new OrderItem((short) 1, "A", 1), new OrderItem((short) 2, "B", 1)));
        PlaceOrderCommand ba = new PlaceOrderCommand("k", CUSTOMER, ServiceTier.DAWN, "주소", "06236",
                new Parcel(1, 1, false, false),
                List.of(new OrderItem((short) 1, "B", 1), new OrderItem((short) 2, "A", 1)));

        assertThat(ab.fingerprint()).isNotEqualTo(ba.fingerprint());
    }

    @Test
    void 구분자_덕분에_필드_경계가_모호해지지_않는다() {
        // 구분자 없이 이어 붙이면 ("ab","c") 와 ("a","bc") 가 같은 문자열이 된다.
        PlaceOrderCommand left = new PlaceOrderCommand("ab", CUSTOMER, ServiceTier.DAWN, "c", "06236",
                new Parcel(1, 1, false, false), List.of(new OrderItem((short) 1, "S", 1)));
        PlaceOrderCommand right = new PlaceOrderCommand("a", CUSTOMER, ServiceTier.DAWN, "bc", "06236",
                new Parcel(1, 1, false, false), List.of(new OrderItem((short) 1, "S", 1)));

        assertThat(left.fingerprint()).isNotEqualTo(right.fingerprint());
    }

    @Test
    void toString_에는_주소도_멱등키도_없다() {
        // CLAUDE.md 로그 규칙. 멱등 키가 로그에 남으면 그 값으로 남의 접수 응답을 재생할 수 있다.
        String text = base().toString();

        assertThat(text).doesNotContain("테헤란로").doesNotContain("06236").doesNotContain("idem-key-1");
        assertThat(text).contains("DAWN");
    }

    @Test
    void 멱등키는_비어_있거나_64자를_넘을_수_없다() {
        assertThatThrownBy(() -> new PlaceOrderCommand("  ", CUSTOMER, ServiceTier.DAWN, "주소", "06236",
                new Parcel(1, 1, false, false), List.of(new OrderItem((short) 1, "S", 1))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("idempotencyKey");

        String tooLong = "k".repeat(PlaceOrderCommand.MAX_IDEMPOTENCY_KEY_LENGTH + 1);
        assertThatThrownBy(() -> new PlaceOrderCommand(tooLong, CUSTOMER, ServiceTier.DAWN, "주소", "06236",
                new Parcel(1, 1, false, false), List.of(new OrderItem((short) 1, "S", 1))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("idempotencyKey");
    }

    @Test
    void 품목이_없으면_거부한다() {
        assertThatThrownBy(() -> new PlaceOrderCommand("k", CUSTOMER, ServiceTier.DAWN, "주소", "06236",
                new Parcel(1, 1, false, false), List.of()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("items");
    }

    @Test
    void 품목_목록은_방어적으로_복사된다() {
        List<OrderItem> mutable = new java.util.ArrayList<>(List.of(new OrderItem((short) 1, "S", 1)));
        PlaceOrderCommand command = new PlaceOrderCommand("k", CUSTOMER, ServiceTier.DAWN, "주소", "06236",
                new Parcel(1, 1, false, false), mutable);
        String before = command.fingerprint();

        mutable.add(new OrderItem((short) 2, "T", 1));

        assertThat(command.items()).hasSize(1);
        assertThat(command.fingerprint()).isEqualTo(before);
    }

    @Test
    void null_인자는_거부한다() {
        assertThatThrownBy(() -> new PlaceOrderCommand("k", null, ServiceTier.DAWN, "주소", "06236",
                new Parcel(1, 1, false, false), List.of(new OrderItem((short) 1, "S", 1))))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlaceOrderCommand("k", CUSTOMER, ServiceTier.DAWN, "주소", "06236",
                null, List.of(new OrderItem((short) 1, "S", 1))))
                .isInstanceOf(NullPointerException.class);
    }
}
