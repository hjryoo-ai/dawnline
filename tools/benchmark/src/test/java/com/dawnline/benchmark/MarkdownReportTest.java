package com.dawnline.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Money;
import com.dawnline.dispatch.domain.optimizer.PlanMetrics;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class MarkdownReportTest {

    private static final Instant AT = Instant.parse("2026-09-06T01:00:00Z");

    private String render() {
        Map<String, StrategySummary> summaries = new LinkedHashMap<>();
        summaries.put("baseline", new StrategySummary("baseline",
                List.of(new RunOutcome(
                        new PlanMetrics(4, 480, 20, 4, 123_456, 7_200, 3, 45, 900),
                        Money.krw(1_234_567), 900))));
        return new MarkdownReport(Dataset.SMALL, 42L, 5, AT).render(summaries);
    }

    @Test
    void 표에_전략과_수치가_들어간다() {
        assertThat(render())
                .contains("| `baseline` |")
                .contains("1,234,567")
                .contains("123,456 m")
                .contains("900 ms");
    }

    @Test
    void 데이터셋과_seed_와_회차가_적힌다() {
        // 이것들이 없으면 표를 다시 낼 수 없다.
        assertThat(render())
                .contains("주문 500")
                .contains("차량 5")
                .contains("seed `42`")
                .contains("5회");
    }

    @Test
    void 환경이_함께_남는다() {
        // 환경 없는 수치는 나중에 비교 대상이 되지 못한다 (Phase 1 k6·Phase 2 EXPLAIN 과 같은 규칙).
        assertThat(render())
                .contains("## 환경")
                .contains("| OS |")
                .contains("| JVM |")
                .contains("| 가용 프로세서 |")
                .contains("| 최대 힙 |")
                .contains("| GC |");
    }

    @Test
    void 시간이_게이트가_아니라는_사실이_리포트에_적힌다() {
        // 표를 보는 사람이 시간 차이를 회귀로 오해하지 않도록 리포트 자체가 말한다.
        assertThat(render()).contains("계획 시간은 기록만 하고 게이트에 넣지 않는다");
    }
}
