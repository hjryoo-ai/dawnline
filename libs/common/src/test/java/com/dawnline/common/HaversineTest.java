package com.dawnline.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class HaversineTest {

    private static final GeoPoint SEOUL_CITY_HALL = GeoPoint.of(37.5663, 126.9779);
    private static final GeoPoint GANGNAM = GeoPoint.of(37.4979, 127.0276);

    @Test
    void 같은_지점은_0_이다() {
        assertThat(Haversine.meters(GANGNAM, GANGNAM)).isZero();
    }

    @Test
    void 대칭이다() {
        assertThat(Haversine.meters(SEOUL_CITY_HALL, GANGNAM))
                .isEqualTo(Haversine.meters(GANGNAM, SEOUL_CITY_HALL));
    }

    @Test
    void 서울시청에서_강남까지_약_8_8km() {
        assertThat(Haversine.km(SEOUL_CITY_HALL, GANGNAM)).isCloseTo(8.8d, within(0.2d));
    }

    @Test
    void km_는_m_를_1000_으로_나눈_값이다() {
        assertThat(Haversine.km(SEOUL_CITY_HALL, GANGNAM))
                .isEqualTo(Haversine.meters(SEOUL_CITY_HALL, GANGNAM) / 1000.0d);
    }

    @Test
    void 지구_반지름이_Redis_의_값과_같다() {
        // fulfillment 의 DB 폴백이 GEOSEARCH 와 같은 순위를 내야 한다 (불변규칙 7).
        // 이 값을 6371km 로 되돌리면 그 동등성이 조용히 깨진다.
        assertThat(Haversine.EARTH_RADIUS_M).isEqualTo(6372797.560856);
    }

    @Test
    void 지구_반대편도_유한하고_절반_둘레_이하다() {
        // asin 의 입력이 1 을 살짝 넘어 NaN 이 되는 경우를 min 으로 막는다.
        double half = Math.PI * Haversine.EARTH_RADIUS_M;

        assertThat(Haversine.meters(GeoPoint.of(0, 0), GeoPoint.of(0, 180)))
                .isFinite()
                .isCloseTo(half, within(1.0d));
    }
}
