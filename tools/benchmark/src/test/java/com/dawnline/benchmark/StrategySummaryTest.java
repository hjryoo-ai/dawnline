package com.dawnline.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.Money;
import com.dawnline.dispatch.domain.optimizer.PlanMetrics;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class StrategySummaryTest {

    private static RunOutcome run(long costKrw, long durationMs) {
        return new RunOutcome(new PlanMetrics(1, 10, 0, 1, 1_000, 600, 2, 30, durationMs),
                Money.krw(costKrw), durationMs);
    }

    @Test
    void 중앙값은_평균이_아니다() {
        // 한 회차의 GC 나 JIT 워밍업이 평균을 끌고 간다 — Phase 1 k6 에서 콜드 한 번이 전체
        // 평균을 4초로 만든 것과 같은 왜곡이다.
        StrategySummary summary = new StrategySummary("s",
                List.of(run(100, 10), run(100, 10), run(100, 10), run(100, 10), run(100, 10_000)));

        assertThat(summary.medianDurationMs()).isEqualTo(10L);
    }

    @Test
    void p95_는_실제로_관측된_값이다() {
        // 보간하면 없는 값을 만든다. 회차가 5회쯤이면 최근접 순위가 정직하다.
        StrategySummary summary = new StrategySummary("s",
                List.of(run(100, 1), run(100, 2), run(100, 3), run(100, 4), run(100, 5)));

        assertThat(summary.p95DurationMs()).isEqualTo(5L);
    }

    @Test
    void 짝수_회차에서도_관측값_하나를_고른다() {
        StrategySummary summary = new StrategySummary("s",
                List.of(run(100, 10), run(100, 20), run(100, 30), run(100, 40)));

        assertThat(summary.medianDurationMs()).isIn(20L, 30L);
    }

    @Test
    void 비용과_지표의_중앙값을_각각_낸다() {
        StrategySummary summary = new StrategySummary("s",
                List.of(run(300, 1), run(100, 2), run(200, 3)));

        assertThat(summary.medianCostKrw()).isEqualTo(200L);
        assertThat(summary.medianUnassigned()).isZero();
        assertThat(summary.medianVehiclesUsed()).isEqualTo(1L);
        assertThat(summary.medianLateStops()).isEqualTo(2L);
        assertThat(summary.medianDistanceM()).isEqualTo(1_000L);
        assertThat(summary.medianAverageLateMinutes()).isEqualTo(15.0d);
    }

    @Test
    void 회차가_없으면_요약을_만들_수_없다() {
        assertThatThrownBy(() -> new StrategySummary("s", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
