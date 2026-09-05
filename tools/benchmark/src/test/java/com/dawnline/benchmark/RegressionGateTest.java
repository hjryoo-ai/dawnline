package com.dawnline.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.Money;
import com.dawnline.dispatch.domain.optimizer.PlanMetrics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 회귀 게이트 (DESIGN.md §6.9). */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RegressionGateTest {

    private static StrategySummary summary(String name, long costKrw, long durationMs) {
        return new StrategySummary(name, List.of(new RunOutcome(
                new PlanMetrics(1, 10, 0, 1, 1_000, 600, 0, 0, durationMs),
                Money.krw(costKrw), durationMs)));
    }

    private static Map<String, StrategySummary> summaries(StrategySummary... values) {
        Map<String, StrategySummary> byName = new LinkedHashMap<>();
        for (StrategySummary value : values) {
            byName.put(value.strategy(), value);
        }
        return byName;
    }

    @Test
    void 기준보다_싸면_통과한다() {
        List<RegressionGate.Failure> failures = new RegressionGate("baseline-nn").evaluate(
                summaries(summary("baseline-nn", 1_000, 10), summary("sweep", 900, 10)));

        assertThat(failures).isEmpty();
    }

    @Test
    void 기준과_같으면_통과한다() {
        // "나쁘면 실패" 다. 같은 값은 나빠지지 않았다 — 여기서 부등호를 잘못 고르면 결정론적인
        // 두 전략이 우연히 같은 비용을 낼 때 아무 이유 없이 빨개진다.
        List<RegressionGate.Failure> failures = new RegressionGate("baseline-nn").evaluate(
                summaries(summary("baseline-nn", 1_000, 10), summary("sweep", 1_000, 10)));

        assertThat(failures).isEmpty();
    }

    @Test
    void 기준보다_비싸면_실패하고_초과율을_말한다() {
        List<RegressionGate.Failure> failures = new RegressionGate("baseline-nn").evaluate(
                summaries(summary("baseline-nn", 1_000, 10), summary("sweep", 1_100, 10)));

        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst().lossPercent()).isEqualTo(10.0d);
        assertThat(failures.getFirst().describe()).contains("sweep").contains("+10.0%");
    }

    @Test
    void 비싼_전략이_여럿이면_전부_알려_준다() {
        // 하나만 알려 주고 멈추면 두 번째는 다음 실행에서야 보인다.
        List<RegressionGate.Failure> failures = new RegressionGate("baseline-nn").evaluate(
                summaries(summary("baseline-nn", 1_000, 10), summary("a", 1_100, 10),
                        summary("b", 1_200, 10)));

        assertThat(failures).extracting(RegressionGate.Failure::strategy)
                .containsExactly("a", "b");
    }

    @Test
    void 게이트는_시간을_보지_않는다() {
        // 같은 실행 안에서 돌므로 비용 비교는 러너에 독립이지만 시간은 아니다. 시간이 100배여도
        // 비용이 싸면 통과다 — 환경 탓으로 빨개지는 게이트는 결국 꺼진다.
        List<RegressionGate.Failure> failures = new RegressionGate("baseline-nn").evaluate(
                summaries(summary("baseline-nn", 1_000, 10), summary("sweep", 900, 1_000)));

        assertThat(failures).isEmpty();
    }

    @Test
    void 기준_전략의_결과가_없으면_조용히_통과하지_않는다() {
        assertThatThrownBy(() -> new RegressionGate("baseline-nn")
                .evaluate(summaries(summary("sweep", 900, 10))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseline-nn");
    }
}
