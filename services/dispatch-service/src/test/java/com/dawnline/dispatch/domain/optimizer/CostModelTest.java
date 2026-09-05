package com.dawnline.dispatch.domain.optimizer;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Money;
import com.dawnline.common.TimeWindow;
import com.dawnline.common.Ids;
import java.time.Duration;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CostModelTest {

    private final CostModel cost = new CostModel();

    private VehicleSpec vehicleWith(long fixed, long perKm, long perMin) {
        return new VehicleSpec(VehicleId.of(Ids.newId()),
                new Capacity(1_000, 1_000),
                new VehicleAttrs("VAN", false, false),
                new TimeWindow(OptimizerFixtures.START, OptimizerFixtures.START.plus(Duration.ofHours(8))),
                VehicleCost.krw(fixed, perKm, perMin));
    }

    @Test
    void 고정비와_거리비와_시간비를_더한다() {
        VehicleSpec vehicle = vehicleWith(30_000, 500, 200);

        // 10 km · 60 분 → 30,000 + 5,000 + 12,000
        assertThat(cost.routeCost(vehicle, 10_000, 3_600, 3)).isEqualTo(Money.krw(47_000));
    }

    @Test
    void 빈_라우트는_고정비도_물지_않는다() {
        // 굴리지 않은 차에 고정비를 물면 미배정보다 비싸 보여 최적화가 차를 억지로 채운다.
        assertThat(cost.routeCost(vehicleWith(30_000, 500, 200), 0, 0, 0)).isEqualTo(Money.ZERO);
    }

    @Test
    void 백미터_구간의_비용이_사라지지_않는다() {
        // 먼저 나누면 500 m 가 0 km 가 되어 짧은 stop 이 공짜가 된다. 곱한 뒤에 나눈다.
        VehicleSpec vehicle = vehicleWith(0, 1_000, 0);

        assertThat(cost.routeCost(vehicle, 500, 0, 1)).isEqualTo(Money.krw(500));
    }

    @Test
    void 일분_미만_구간의_비용도_사라지지_않는다() {
        VehicleSpec vehicle = vehicleWith(0, 0, 600);

        assertThat(cost.routeCost(vehicle, 0, 30, 1)).isEqualTo(Money.krw(300));
    }

    @Test
    void 나머지는_버린다() {
        // 원 단위 아래가 없는 통화다. 두 전략을 같은 규칙으로 재는 한 비교에는 영향이 없다.
        VehicleSpec vehicle = vehicleWith(0, 1, 0);

        assertThat(cost.routeCost(vehicle, 1_999, 0, 1)).isEqualTo(Money.krw(1));
    }
}
