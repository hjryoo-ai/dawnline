package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Haversine;
import com.dawnline.common.error.ValidationException;

/**
 * 대권 거리에 도로계수를 곱하고 평균 속도로 시간을 만드는 기본 {@link DistanceProvider}
 * (DESIGN.md §6.2).
 *
 * <p>파라미터(도로계수 1.3, 평균 25 km/h)는 <strong>캠프 설정값</strong>이라 생성자로 받는다 —
 * 도심과 외곽은 같은 직선거리에서 다른 시간이 나오고, 코드에 상수를 두면 그 사실을 표현할 수 없다.
 *
 * <p>공식과 지구 반지름은 {@link Haversine} 하나뿐이다. fulfillment 도 같은 것을 쓴다 — 이유는
 * 서로 다르지만 <em>같은 두 지점에 대해 플랫폼이 두 개의 거리를 말하면 안 되기</em> 때문이다.
 */
public final class HaversineDistance implements DistanceProvider {

    private final double roadFactor;
    private final double metersPerSecond;

    /**
     * @param roadFactor       직선거리 → 도로거리 계수 (1 이상)
     * @param averageSpeedKmh  평균 주행 속도(km/h)
     */
    public HaversineDistance(double roadFactor, double averageSpeedKmh) {
        if (!(roadFactor >= 1.0d) || !Double.isFinite(roadFactor)) {
            throw ValidationException.field("roadFactor", roadFactor, "도로계수는 1 이상이어야 합니다");
        }
        if (!(averageSpeedKmh > 0.0d) || !Double.isFinite(averageSpeedKmh)) {
            throw ValidationException.field("averageSpeedKmh", averageSpeedKmh, "평균 속도는 양수여야 합니다");
        }
        this.roadFactor = roadFactor;
        this.metersPerSecond = averageSpeedKmh * 1000.0d / 3600.0d;
    }

    @Override
    public Travel between(GeoPoint from, GeoPoint to) {
        double meters = Haversine.meters(from, to) * roadFactor;
        // 올림이 아니라 반올림이다. 5,000 stop 라우트에서 stop 마다 1초씩 올리면 83분이 는다.
        return new Travel((int) Math.round(meters), (int) Math.round(meters / metersPerSecond));
    }
}
