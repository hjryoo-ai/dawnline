package com.dawnline.fulfillment.domain;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Haversine;

/**
 * 두 좌표 사이의 대권 거리 (하버사인) — 순수 함수.
 *
 * <h2>구현은 {@link Haversine} 하나뿐이다</h2>
 * 공식과 지구 반지름은 {@code libs/common} 에 있고 이 클래스는 <strong>이 서비스가 왜 그 값을
 * 신경 쓰는지</strong>를 들고 있다. dispatch 도 같은 공식을 쓰지만(§6.2) 이유는 다르다 — 그래서
 * 이유는 각자, 구현은 하나다.
 *
 * <h2>지구 반지름이 6371 km 가 아닌 이유 (이 서비스의 이유)</h2>
 * 이 클래스의 결과가 Redis {@code GEOSEARCH} 의 거리와 <em>같은 순위</em>를 내야 한다 —
 * 불변규칙 7 의 폴백은 "동작한다" 가 아니라 <strong>"같은 답을 낸다"</strong> 여야 하기 때문이다.
 * 그래서 관례적인 6371 km 가 아니라 Redis 가 쓰는 값(6372797.560856 m)을 쓴다.
 *
 * <p>차이를 없애도 완전히 같지는 않다. Redis 는 좌표를 52비트 geohash 로 양자화해 저장하므로
 * 약 0.6 m 의 오차가 남는다. 그래서 <em>거리 자체</em>는 허용 오차로 비교하고, 판정이 의존하는
 * <em>순위</em>는 정확히 같은지 본다(동등성 테스트).
 *
 * <p>순위가 뒤집히려면 두 FC 가 이 오차(수 m) 안에서 같은 거리에 있어야 한다. 그런 동률에서도
 * 답이 흔들리지 않도록, 순위 비교의 마지막 기준은 거리가 아니라 FC 코드다({@link FcSelection}).
 */
public final class GeoDistance {

    /** Redis GEO 명령의 지구 반지름(m). 값의 출처와 근거는 {@link Haversine} 에 있다. */
    public static final double EARTH_RADIUS_M = Haversine.EARTH_RADIUS_M;

    private GeoDistance() {
    }

    /**
     * 두 지점 사이의 거리(km).
     *
     * @param from 시작점
     * @param to   끝점
     */
    public static double km(GeoPoint from, GeoPoint to) {
        return Haversine.km(from, to);
    }
}
