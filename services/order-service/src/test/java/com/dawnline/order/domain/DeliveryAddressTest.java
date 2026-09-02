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

    @Test
    void 좌표와_맞지_않는_geohash7_은_거부한다() {
        // 저장된 geohash 가 좌표와 어긋나면 파티션 키와 실제 위치가 달라진다.
        // 그 어긋남은 라우팅이 이상해진 뒤에야 드러난다.
        assertThatThrownBy(() -> new DeliveryAddress(LINE, "06236", GANGNAM, "wydm000"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("geohash7")
                .hasMessageContaining("wydm6d6");
    }

    @ParameterizedTest
    @ValueSource(strings = {"wydm6d", "wydm6d66", ""})
    void geohash7_의_길이가_다르면_거부한다(String wrong) {
        assertThatThrownBy(() -> new DeliveryAddress(LINE, "06236", GANGNAM, wrong))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("geohash7");
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
