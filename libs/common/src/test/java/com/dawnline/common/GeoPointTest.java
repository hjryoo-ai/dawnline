package com.dawnline.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dawnline.common.error.CommonErrorCode;
import com.dawnline.common.error.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("GeoPoint — WGS84 좌표 값 객체")
class GeoPointTest {

    @Test
    void 생성_유효한_좌표면_값을_그대로_보관한다() {
        GeoPoint point = GeoPoint.of(37.4979d, 127.0276d);

        assertThat(point.lat()).isEqualTo(37.4979d);
        assertThat(point.lng()).isEqualTo(127.0276d);
        assertThat(point).isEqualTo(new GeoPoint(37.4979d, 127.0276d));
        assertThat(point).hasSameHashCodeAs(new GeoPoint(37.4979d, 127.0276d));
    }

    @ParameterizedTest
    @CsvSource({"-90.0, -180.0", "90.0, 180.0", "0.0, 0.0", "-90.0, 180.0", "90.0, -180.0"})
    void 생성_경계값은_허용한다(double lat, double lng) {
        assertThat(new GeoPoint(lat, lng).lat()).isEqualTo(lat);
    }

    @ParameterizedTest
    @ValueSource(doubles = {90.000001d, -90.000001d, 91.0d, -1000.0d})
    void 생성_위도가_범위를_벗어나면_ValidationException(double lat) {
        assertThatThrownBy(() -> new GeoPoint(lat, 127.0d))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("lat");
    }

    @ParameterizedTest
    @ValueSource(doubles = {180.000001d, -180.000001d, 360.0d})
    void 생성_경도가_범위를_벗어나면_ValidationException(double lng) {
        assertThatThrownBy(() -> new GeoPoint(37.5d, lng))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("lng");
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void 생성_위도가_NaN_이나_무한대면_거부한다(double lat) {
        assertThatThrownBy(() -> new GeoPoint(lat, 127.0d))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("유한한");
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void 생성_경도가_NaN_이나_무한대면_거부한다(double lng) {
        assertThatThrownBy(() -> new GeoPoint(37.5d, lng))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("유한한");
    }

    @Test
    void 생성_실패_예외는_기계가_읽을_수_있는_상세를_담는다() {
        ValidationException thrown =
                assertThrows(ValidationException.class, () -> new GeoPoint(120.0d, 0.0d));

        assertThat(thrown.errorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED);
        assertThat(thrown.status()).isEqualTo(400);
        assertThat(thrown.details()).containsEntry("field", "lat").containsEntry("value", "120.0");
    }

    @Test
    void geohash5_와_geohash7_은_각각_권역과_stop_정밀도로_인코딩한다() {
        GeoPoint gangnam = GeoPoint.of(37.4979d, 127.0276d);

        assertThat(gangnam.geohash5()).isEqualTo("wydm6").hasSize(5);
        assertThat(gangnam.geohash7()).isEqualTo("wydm6d6").hasSize(7);
        assertThat(gangnam.geohash7()).startsWith(gangnam.geohash5());
    }
}
