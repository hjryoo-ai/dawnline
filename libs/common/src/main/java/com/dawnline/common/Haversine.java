package com.dawnline.common;

import java.util.Objects;

/**
 * 두 좌표 사이의 대권 거리 (하버사인) — 순수 함수.
 *
 * <h2>왜 서비스가 아니라 여기 있는가</h2>
 * fulfillment 는 캠프–FC 거리를(§5.2), dispatch 는 stop 사이 거리를(§6.2) 잰다. 쓰는 이유는 다르지만
 * <strong>같은 두 지점에 대해 플랫폼이 두 개의 거리를 말하면 안 된다.</strong> 공식은 교과서지만
 * 지구 반지름은 선택이고, 그 선택이 갈라지는 것이 문제다.
 *
 * <h2>지구 반지름이 6371 km 가 아닌 이유</h2>
 * 이 값은 <strong>Redis 의 GEO 명령이 쓰는 값과 같다</strong>({@code geohash.c} 의
 * {@code EARTH_RADIUS_IN_METERS}). 관례적인 평균 반지름 6371 km 와 0.028% 차이인데, 그 차이를
 * 없애는 편이 맞다 — fulfillment 의 DB 폴백이 Redis {@code GEOSEARCH} 와 <em>같은 순위</em>를
 * 내야 하기 때문이다(불변규칙 7 의 폴백은 "동작한다" 가 아니라 <strong>"같은 답을 낸다"</strong>다).
 *
 * <p>dispatch 에는 Redis 대응이 필요 없지만 같은 값을 쓴다. 반지름을 서비스마다 고르게 두면
 * 언젠가 두 화면이 같은 구간에 대해 다른 거리를 보여 준다.
 *
 * <p>차이를 없애도 완전히 같지는 않다. Redis 는 좌표를 52비트 geohash 로 양자화해 저장하므로 약
 * 0.6 m 오차가 남는다. 그래서 <em>거리 자체</em>는 허용 오차로 비교하고, 판정이 의존하는
 * <em>순위</em>는 정확히 같은지 본다.
 */
public final class Haversine {

    /** Redis GEO 명령의 지구 반지름(m). */
    public static final double EARTH_RADIUS_M = 6372797.560856;

    private Haversine() {
    }

    /**
     * 두 지점 사이의 거리(m).
     *
     * @param from 시작점
     * @param to   끝점
     */
    public static double meters(GeoPoint from, GeoPoint to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        double lat1 = Math.toRadians(from.lat());
        double lat2 = Math.toRadians(to.lat());
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(to.lng() - from.lng());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /**
     * 두 지점 사이의 거리(km).
     *
     * @param from 시작점
     * @param to   끝점
     */
    public static double km(GeoPoint from, GeoPoint to) {
        return meters(from, to) / 1000.0;
    }
}
