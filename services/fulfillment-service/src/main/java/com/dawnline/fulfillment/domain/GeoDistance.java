package com.dawnline.fulfillment.domain;

import com.dawnline.common.GeoPoint;
import java.util.Objects;

/**
 * 두 좌표 사이의 대권 거리 (하버사인) — 순수 함수.
 *
 * <h2>지구 반지름이 6371 km 가 아닌 이유</h2>
 * 이 값은 <strong>Redis 의 GEO 명령이 쓰는 값과 같다</strong>(6372797.560856 m). 관례적인 평균
 * 반지름 6371 km 와 0.028% 차이인데, 여기서는 그 차이를 없애는 편이 맞다 — 이 클래스의 결과가
 * Redis {@code GEOSEARCH} 의 거리와 <em>같은 순위</em>를 내야 하기 때문이다(불변규칙 7의 폴백은
 * "동작한다" 가 아니라 <strong>"같은 답을 낸다"</strong> 여야 한다).
 *
 * <p>차이를 없애도 완전히 같지는 않다. Redis 는 좌표를 52비트 geohash 로 양자화해 저장하므로
 * 약 0.6 m 의 오차가 남는다. 그래서 <em>거리 자체</em>는 허용 오차로 비교하고, 판정이 의존하는
 * <em>순위</em>는 정확히 같은지 본다(동등성 테스트).
 *
 * <p>순위가 뒤집히려면 두 FC 가 이 오차(수 m) 안에서 같은 거리에 있어야 한다. 그런 동률에서도
 * 답이 흔들리지 않도록, 순위 비교의 마지막 기준은 거리가 아니라 FC 코드다({@link FcSelection}).
 */
public final class GeoDistance {

    /** Redis GEO 명령의 지구 반지름(m). {@code geohash.c} 의 {@code EARTH_RADIUS_IN_METERS} 다. */
    public static final double EARTH_RADIUS_M = 6372797.560856;

    private GeoDistance() {
    }

    /**
     * 두 지점 사이의 거리(km).
     *
     * @param from 시작점
     * @param to   끝점
     */
    public static double km(GeoPoint from, GeoPoint to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        double lat1 = Math.toRadians(from.lat());
        double lat2 = Math.toRadians(to.lat());
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(to.lng() - from.lng());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * (EARTH_RADIUS_M / 1000.0) * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
