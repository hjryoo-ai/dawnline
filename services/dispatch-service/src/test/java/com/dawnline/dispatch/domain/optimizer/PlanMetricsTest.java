package com.dawnline.dispatch.domain.optimizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.dawnline.common.error.ValidationException;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PlanMetricsTest {

    private PlanMetrics metrics(int lateStops, long totalLateMinutes, int assigned, int unassigned) {
        return new PlanMetrics(2, assigned, unassigned, 2, 10_000, 3_600,
                lateStops, totalLateMinutes, 1_200);
    }

    @Test
    void 평균_지각은_지각한_stop_으로_나눈다() {
        // 전체 stop 으로 나누면 stop 을 늘리기만 해도 좋아 보인다.
        assertThat(metrics(4, 100, 500, 0).averageLateMinutes()).isCloseTo(25.0d, within(0.001d));
    }

    @Test
    void 지각이_없으면_평균은_0_이다() {
        assertThat(metrics(0, 0, 500, 0).averageLateMinutes()).isZero();
    }

    @Test
    void 미배정률은_전체_주문_대비다() {
        assertThat(metrics(0, 0, 990, 10).unassignedRatio()).isCloseTo(0.01d, within(0.0001d));
    }

    @Test
    void 후보가_없으면_미배정률은_0_이다() {
        assertThat(metrics(0, 0, 0, 0).unassignedRatio()).isZero();
    }

    @Test
    void 음수_지표는_만들_수_없다() {
        assertThatThrownBy(() -> metrics(-1, 0, 0, 0)).isInstanceOf(ValidationException.class);
    }
}
