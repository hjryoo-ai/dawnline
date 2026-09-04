package com.dawnline.sim.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 실행 결과의 요약. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ScenarioReportTest {

    private static ScenarioReport report(int requested, int accepted, Map<String, Integer> codes) {
        return new ScenarioReport("smoke", requested, accepted, 0,
                requested - accepted, 0, 0, codes,
                ScenarioReport.Latency.empty(), Duration.ofSeconds(10));
    }

    @Test
    void 보낸_만큼_접수되어야_성공이다() {
        assertThat(report(200, 200, Map.of()).isSuccess()).isTrue();
        assertThat(report(200, 199, Map.of("validation-failed", 1)).isSuccess()).isFalse();
    }

    @Test
    void 백분위는_표본을_정렬해서_고른다() {
        long[] nanos = new long[100];
        for (int i = 0; i < nanos.length; i++) {
            nanos[i] = (i + 1) * 1_000_000L;      // 1ms ~ 100ms
        }
        ScenarioReport.Latency latency = ScenarioReport.Latency.of(nanos);

        assertThat(latency.p50()).isEqualTo(50.0);
        assertThat(latency.p95()).isEqualTo(95.0);
        assertThat(latency.p99()).isEqualTo(99.0);
        assertThat(latency.max()).isEqualTo(100.0);
    }

    @Test
    void 표본이_비면_0_이다() {
        assertThat(ScenarioReport.Latency.of(new long[0])).isEqualTo(ScenarioReport.Latency.empty());
    }

    @Test
    void 표본이_하나여도_깨지지_않는다() {
        ScenarioReport.Latency latency = ScenarioReport.Latency.of(new long[] {5_000_000L});
        assertThat(latency.p50()).isEqualTo(5.0);
        assertThat(latency.p99()).isEqualTo(5.0);
    }

    @Test
    void 표는_실패_코드를_한_줄씩_보여_준다() {
        String markdown = report(200, 197,
                Map.of("validation-failed", 2, "tier-not-serviceable", 1)).toMarkdown();

        assertThat(markdown)
                .contains("| 보낸 주문 | 200 |")
                .contains("| 접수(201) | 197 (98.5%) |")
                .contains("| — tier-not-serviceable | 1 |")
                .contains("| — validation-failed | 2 |");
    }
}
