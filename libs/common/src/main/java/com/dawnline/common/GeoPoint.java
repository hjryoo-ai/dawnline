package com.dawnline.common;

import com.dawnline.common.error.ValidationException;

/**
 * WGS84 좌표 값 객체.
 *
 * <p>DESIGN.md §7.1 에 따라 좌표는 {@code NUMERIC(9,6)} / {@code double} 로 다룬다.
 * 금액과 달리 좌표는 부동소수를 쓴다(거리 계산이 실수 연산이므로).
 *
 * @param lat 위도 (-90 ~ 90)
 * @param lng 경도 (-180 ~ 180)
 */
public record GeoPoint(double lat, double lng) {

    public GeoPoint {
        if (!Double.isFinite(lat)) {
            throw ValidationException.field("lat", lat, "위도는 유한한 수여야 합니다");
        }
        if (!Double.isFinite(lng)) {
            throw ValidationException.field("lng", lng, "경도는 유한한 수여야 합니다");
        }
        if (lat < -90.0d || lat > 90.0d) {
            throw ValidationException.field("lat", lat, "위도는 -90 이상 90 이하여야 합니다");
        }
        if (lng < -180.0d || lng > 180.0d) {
            throw ValidationException.field("lng", lng, "경도는 -180 이상 180 이하여야 합니다");
        }
    }

    /** {@code new GeoPoint(lat, lng)} 의 읽기 쉬운 별칭. */
    public static GeoPoint of(double lat, double lng) {
        return new GeoPoint(lat, lng);
    }

    /** 이 좌표의 geohash (권역 매핑용 5자리, 약 4.9km). */
    public String geohash5() {
        return Geohash.encode(this, Geohash.ZONE_PRECISION);
    }

    /** 이 좌표의 geohash (stop 통합·거리 캐시용 7자리, 약 153m). */
    public String geohash7() {
        return Geohash.encode(this, Geohash.STOP_PRECISION);
    }
}
