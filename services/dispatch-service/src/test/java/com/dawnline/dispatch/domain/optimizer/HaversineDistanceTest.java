package com.dawnline.dispatch.domain.optimizer;

import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.CITY_HALL;
import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.GANGNAM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.dawnline.common.Haversine;
import com.dawnline.common.error.ValidationException;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class HaversineDistanceTest {

    private final DistanceProvider distance = new HaversineDistance(1.3d, 25.0d);

    @Test
    void 도로계수를_직선거리에_곱한다() {
        double straight = Haversine.meters(CITY_HALL, GANGNAM);

        assertThat(distance.between(CITY_HALL, GANGNAM).meters())
                .isCloseTo((int) Math.round(straight * 1.3d), within(1));
    }

    @Test
    void 시간은_평균_속도에서_나온다() {
        Travel travel = distance.between(CITY_HALL, GANGNAM);

        // 25 km/h = 초당 6.944 m
        assertThat(travel.seconds())
                .isCloseTo((int) Math.round(travel.meters() / (25_000.0d / 3600.0d)), within(1));
    }

    @Test
    void 같은_지점은_0_이다() {
        assertThat(distance.between(CITY_HALL, CITY_HALL)).isEqualTo(Travel.NONE);
    }

    @Test
    void 대칭이다() {
        // 대칭이 아니면 2-opt 의 구간 뒤집기가 비용을 바꾼다.
        assertThat(distance.between(CITY_HALL, GANGNAM))
                .isEqualTo(distance.between(GANGNAM, CITY_HALL));
    }

    @Test
    void 공식은_libs_common_하나뿐이다() {
        // 같은 두 지점에 대해 플랫폼이 두 개의 거리를 말하면 안 된다.
        assertThat(Haversine.EARTH_RADIUS_M).isEqualTo(6372797.560856);
    }

    @Test
    void 도로계수가_1_미만이면_거부한다() {
        assertThatThrownBy(() -> new HaversineDistance(0.9d, 25.0d))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void 평균_속도가_0_이면_거부한다() {
        // 0 이면 시간이 무한이 되어 근무창 판정이 전부 불가가 된다.
        assertThatThrownBy(() -> new HaversineDistance(1.3d, 0.0d))
                .isInstanceOf(ValidationException.class);
    }
}
