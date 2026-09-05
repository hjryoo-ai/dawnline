package com.dawnline.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 하버사인 (§7.2 폴백). Redis 와 같은 지구 반지름을 쓰는지까지 본다. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GeoDistanceTest {

    private static final GeoPoint SEOUL_CITY_HALL = new GeoPoint(37.5665, 126.9780);
    private static final GeoPoint GANGNAM = new GeoPoint(37.4979, 127.0276);

    @Test
    void 같은_지점은_0_이다() {
        assertThat(GeoDistance.km(GANGNAM, GANGNAM)).isZero();
    }

    @Test
    void 대칭이다() {
        assertThat(GeoDistance.km(SEOUL_CITY_HALL, GANGNAM))
                .isEqualTo(GeoDistance.km(GANGNAM, SEOUL_CITY_HALL));
    }

    @Test
    void 서울시청에서_강남까지_약_8_8km() {
        // 직선거리다. 실제 도로 거리(약 11 km)와 다른 것이 정상이고, 대체 FC 선택은 간선의
        // 상한(50 km)을 보는 것이라 직선으로 충분하다 (ADR-021 결정 3-a).
        assertThat(GeoDistance.km(SEOUL_CITY_HALL, GANGNAM))
                .isCloseTo(8.80, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    void 지구_반지름이_Redis_의_값과_같다() {
        // 관례적인 6371 km 가 아니라 Redis geohash.c 의 EARTH_RADIUS_IN_METERS 다.
        // 폴백이 "같은 답" 을 내야 하므로 굳이 다르게 둘 이유가 없다 (§7.2, 불변규칙 7).
        assertThat(GeoDistance.EARTH_RADIUS_M).isEqualTo(6372797.560856);

        // 6371 km 로 잰 값과는 0.028% 어긋난다 — 그 차이를 없앤 것이 이 상수의 목적이다.
        double withConventionalRadius = 2 * 6371.0 * Math.asin(Math.sqrt(haversineTerm()));
        assertThat(GeoDistance.km(SEOUL_CITY_HALL, GANGNAM))
                .isNotEqualTo(withConventionalRadius)
                .isCloseTo(withConventionalRadius, org.assertj.core.data.Percentage.withPercentage(0.05));
    }

    private static double haversineTerm() {
        double lat1 = Math.toRadians(SEOUL_CITY_HALL.lat());
        double lat2 = Math.toRadians(GANGNAM.lat());
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(GANGNAM.lng() - SEOUL_CITY_HALL.lng());
        return Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
    }
}
