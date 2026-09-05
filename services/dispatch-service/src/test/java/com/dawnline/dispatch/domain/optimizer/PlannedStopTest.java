package com.dawnline.dispatch.domain.optimizer;

import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.GANGNAM;
import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.START;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.common.error.ValidationException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PlannedStopTest {

    private static final TimeWindow PROMISED =
            new TimeWindow(START, START.plus(Duration.ofHours(2)));

    private static Stop stopWithPromise() {
        return new Stop(GANGNAM, List.of(OrderId.of(Ids.newId())), Parcel.EMPTY, PROMISED, 90, 0);
    }

    @Test
    void 창_안에_도착하면_지각이_아니다() {
        PlannedStop planned = new PlannedStop(1, stopWithPromise(),
                PROMISED.end().minusSeconds(1), PROMISED.end());

        assertThat(planned.lateMinutes()).isZero();
    }

    @Test
    void 창_끝에_정확히_도착하면_지각이_아니다() {
        // 반열린 구간이지만 "끝에 도착" 은 지각이 아니다 — 약속은 그때까지 배송한다는 것이다.
        PlannedStop planned = new PlannedStop(1, stopWithPromise(), PROMISED.end(), PROMISED.end());

        assertThat(planned.lateMinutes()).isZero();
    }

    @Test
    void 창을_넘으면_분_단위로_센다() {
        PlannedStop planned = new PlannedStop(1, stopWithPromise(),
                PROMISED.end().plus(Duration.ofMinutes(17)), PROMISED.end().plus(Duration.ofMinutes(19)));

        assertThat(planned.lateMinutes()).isEqualTo(17L);
    }

    @Test
    void 지각_기준은_도착이지_출발이_아니다() {
        // 고객이 겪는 것은 물건이 도착한 시각이다.
        PlannedStop planned = new PlannedStop(1, stopWithPromise(),
                PROMISED.end().minusSeconds(60), PROMISED.end().plus(Duration.ofMinutes(30)));

        assertThat(planned.lateMinutes()).isZero();
    }

    @Test
    void 출발이_도착보다_앞설_수_없다() {
        assertThatThrownBy(() -> new PlannedStop(1, stopWithPromise(),
                PROMISED.end(), PROMISED.end().minusSeconds(1)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void 방문_순번은_1_이상이다() {
        assertThatThrownBy(() -> new PlannedStop(0, stopWithPromise(), START, START))
                .isInstanceOf(ValidationException.class);
    }
}
