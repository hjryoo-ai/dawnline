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
        return render(new SourceVersion("abc1234", true));
    }

    private String render(SourceVersion source) {
        Map<String, StrategySummary> summaries = new LinkedHashMap<>();
        summaries.put("baseline", new StrategySummary("baseline",
                List.of(new RunOutcome(
                        new PlanMetrics(4, 480, 20, 4, 123_456, 7_200, 3, 45, 900),
                        Money.krw(1_234_567), 900))));
        return new MarkdownReport(Dataset.SMALL, 42L, 5, AT, source).render(summaries);
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
    void 헤더에_커밋과_전략_이름이_들어간다() {
        // 리포트의 신원은 커밋·seed·전략 이름 셋이다 (§6.9). seed 는 위 테스트가 본다.
        assertThat(render())
                .contains("커밋 `abc1234`")
                .contains("전략 `baseline`")
                .contains("커밋이 같은지 먼저 본다");
    }

    @Test
    void 더러운_작업_트리는_재현할_수_없다고_적는다() {
        // 커밋만 적으면 "그 커밋에서 나온 수치" 로 읽힌다. 수정된 트리의 수치는 어떤 커밋에도
        // 귀속되지 않으므로, 비교 대상으로 쓰이기 전에 그 사실이 보여야 한다.
        assertThat(render(new SourceVersion("abc1234", false)))
                .contains("커밋되지 않은 수정")
                .contains("재현할 수 없다");
    }

    @Test
    void git_을_쓸_수_없으면_부재를_값으로_적는다() {
        // 헤더에서 조용히 빠지면 다음 사람은 "적는 걸 잊었다" 와 "알 수 없었다" 를 구별할 수 없다.
        assertThat(render(SourceVersion.UNKNOWN)).contains("커밋 `unknown`(git 없음)");
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
