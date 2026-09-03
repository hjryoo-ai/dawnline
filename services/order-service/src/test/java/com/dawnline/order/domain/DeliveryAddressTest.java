package com.dawnline.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Geohash;
import com.dawnline.common.error.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("DeliveryAddress — 배송지 (DESIGN.md §5.1)")
class DeliveryAddressTest {

    private static final GeoPoint GANGNAM = GeoPoint.of(37.4979, 127.0276);
    private static final String LINE = "서울 강남구 테헤란로 1";

    @Test
    void of_는_좌표에서_geohash7_을_계산한다() {
        DeliveryAddress address = DeliveryAddress.of(LINE, "06236", GANGNAM);

        assertThat(address.geohash7()).hasSize(Geohash.STOP_PRECISION);
        assertThat(address.geohash7()).isEqualTo(Geohash.encode(GANGNAM, Geohash.STOP_PRECISION));
        // §4.3 예시의 값이다. 설계서와 구현이 같은 격자를 가리키는지 못박는다.
        assertThat(address.geohash7()).isEqualTo("wydm6d6");
    }

    @Test
    void geohash5_는_geohash7_의_앞_다섯_자리다() {
        DeliveryAddress address = DeliveryAddress.of(LINE, "06236", GANGNAM);

        assertThat(address.geohash5()).isEqualTo(address.geohash7().substring(0, 5));
        assertThat(address.geohash5()).hasSize(Geohash.ZONE_PRECISION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"wydm6d", "wydm6d66", ""})
    void geohash7_의_길이가_다르면_거부한다(String wrong) {
        assertThatThrownBy(() -> new DeliveryAddress(LINE, "06236", GANGNAM, wrong))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("geohash7");
    }

    @ParameterizedTest
    @ValueSource(strings = {"wydmail", "WYDM6D6", "wydm6d-"})
    void geohash_알파벳에_없는_문자는_거부한다(String wrong) {
        // a·i·l·o 와 대문자는 base32 알파벳에 없다. 길이만 보면 통과하고,
        // 그 행은 어느 격자에도 속하지 않은 채 stop 통합에서만 조용히 튄다.
        assertThatThrownBy(() -> new DeliveryAddress(LINE, "06236", GANGNAM, wrong))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("geohash7");
    }

    @Test
    void of_는_좌표를_소수점_6자리로_맞춰_저장한다() {
        // DB 가 NUMERIC(9,6) 이라 읽어 올 때는 어차피 6자리다. 원본 정밀도로 geohash 를
        // 계산해 두면 셀 경계 근처 주소가 저장·조회 왕복에서 다른 셀이 된다.
        DeliveryAddress address = DeliveryAddress.of(LINE, "06236", GeoPoint.of(37.49791234567, 127.02761987654));

        assertThat(address.point().lat()).isEqualTo(37.497912);
        assertThat(address.point().lng()).isEqualTo(127.027620);
        assertThat(address.isGeohashConsistent()).isTrue();
    }

    @Test
    void of_로_만든_주소는_저장_정밀도로_왕복해도_geohash_가_같다() {
        // 영속화 왕복(NUMERIC(9,6) → double)을 흉내 낸다.
        DeliveryAddress original = DeliveryAddress.of(LINE, "06236", GeoPoint.of(37.49791234567, 127.02761987654));

        GeoPoint roundTripped = GeoPoint.of(
                new java.math.BigDecimal(String.valueOf(original.point().lat()))
                        .setScale(6, java.math.RoundingMode.HALF_UP).doubleValue(),
                new java.math.BigDecimal(String.valueOf(original.point().lng()))
                        .setScale(6, java.math.RoundingMode.HALF_UP).doubleValue());
        DeliveryAddress restored = new DeliveryAddress(
                original.line(), original.postalCode(), roundTripped, original.geohash7());

        assertThat(restored).isEqualTo(original);
        assertThat(restored.isGeohashConsistent()).isTrue();
    }

    @Test
    void 손으로_만든_불일치는_거부하지_않고_진단으로만_알린다() {
        // 여기서 예외를 던지면 저장된 주문을 영영 읽지 못하는 경로가 생긴다.
        // 저장된 geohash7 은 그 주문이 발행한 이벤트에 실린 값이라 재계산값보다 그쪽이 진실이다.
        DeliveryAddress mismatched = new DeliveryAddress(LINE, "06236", GANGNAM, "wydm000");

        assertThat(mismatched.geohash7()).isEqualTo("wydm000");
        assertThat(mismatched.isGeohashConsistent()).isFalse();
        assertThat(DeliveryAddress.of(LINE, "06236", GANGNAM).isGeohashConsistent()).isTrue();
    }

    @Test
    void 주소가_비어_있으면_거부한다() {
        assertThatThrownBy(() -> DeliveryAddress.of("   ", "06236", GANGNAM))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("line");
    }

    @Test
    void 우편번호가_비어_있으면_거부한다() {
        assertThatThrownBy(() -> DeliveryAddress.of(LINE, "", GANGNAM))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("postalCode");
    }

    @Test
    void 지나치게_긴_주소는_거부한다() {
        // §10 입력 검증: 주소 길이 제한.
        assertThatThrownBy(() -> DeliveryAddress.of("가".repeat(201), "06236", GANGNAM))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("line");
    }

    @Test
    void 앞뒤_공백은_다듬는다() {
        DeliveryAddress address = DeliveryAddress.of("  " + LINE + "  ", " 06236 ", GANGNAM);

        assertThat(address.line()).isEqualTo(LINE);
        assertThat(address.postalCode()).isEqualTo("06236");
    }

    @Test
    void toString_은_전체_주소를_마스킹한다() {
        // CLAUDE.md 로그 규칙: 전체 주소·고객 식별 정보는 로그 금지.
        // 애그리거트가 통째로 로그에 실리는 경로는 반드시 생기므로 여기서 막는다.
        String rendered = DeliveryAddress.of(LINE, "06236", GANGNAM).toString();

        assertThat(rendered).doesNotContain("테헤란로");
        assertThat(rendered).contains("line=***");
        // 디버깅에 필요한 위치 정보는 남는다.
        assertThat(rendered).contains("wydm6d6");
    }

    @Test
    void 같은_값이면_같다() {
        assertThat(DeliveryAddress.of(LINE, "06236", GANGNAM))
                .isEqualTo(DeliveryAddress.of(LINE, "06236", GANGNAM));
    }
}
