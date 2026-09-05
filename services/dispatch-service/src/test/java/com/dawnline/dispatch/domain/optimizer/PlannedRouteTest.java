package com.dawnline.dispatch.domain.optimizer;

import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.GANGNAM;
import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.START;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.Ids;
import com.dawnline.common.Money;
import com.dawnline.common.TimeWindow;
import com.dawnline.common.error.ValidationException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PlannedRouteTest {

    private static final TimeWindow PROMISED =
            new TimeWindow(START, START.plus(Duration.ofHours(2)));

    private static Stop stopWith(int orderCount) {
        List<OrderId> ids = java.util.stream.IntStream.range(0, orderCount)
                .mapToObj(i -> OrderId.of(Ids.newId())).toList();
        return new Stop(GANGNAM, ids, Parcel.EMPTY, PROMISED, 90, 0);
    }

    private static PlannedStop planned(int seq, Stop stop, java.time.Instant arrival) {
        return new PlannedStop(seq, stop, arrival, arrival.plusSeconds(stop.serviceSeconds()));
    }

    @Test
    void 주문_수는_stop_수가_아니라_통합된_주문_수다() {
        PlannedRoute route = new PlannedRoute(VehicleId.of(Ids.newId()),
                List.of(planned(1, stopWith(2), START), planned(2, stopWith(3), START)),
                1_000, 600, Money.krw(10_000));

        assertThat(route.stops()).hasSize(2);
        assertThat(route.orderCount()).isEqualTo(5);
    }

    @Test
    void 지각한_stop_을_센다() {
        PlannedRoute route = new PlannedRoute(VehicleId.of(Ids.newId()),
                List.of(planned(1, stopWith(1), PROMISED.end().minusSeconds(60)),
                        planned(2, stopWith(1), PROMISED.end().plus(Duration.ofMinutes(10)))),
                1_000, 600, Money.krw(10_000));

        assertThat(route.lateStopCount()).isEqualTo(1L);
    }

    @Test
    void 방문_순번이_끊기면_거부한다() {
        // seq 는 1부터 연속이어야 한다 — route.assigned 계약의 불변식이기도 하다.
        assertThatThrownBy(() -> new PlannedRoute(VehicleId.of(Ids.newId()),
                List.of(planned(1, stopWith(1), START), planned(3, stopWith(1), START)),
                1_000, 600, Money.ZERO))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void 빈_라우트는_만들지_않는다() {
        assertThatThrownBy(() -> new PlannedRoute(VehicleId.of(Ids.newId()), List.of(), 0, 0, Money.ZERO))
                .isInstanceOf(ValidationException.class);
    }
}
