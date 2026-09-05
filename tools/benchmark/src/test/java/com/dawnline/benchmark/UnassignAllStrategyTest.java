package com.dawnline.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Money;
import com.dawnline.dispatch.domain.optimizer.Candidate;
import com.dawnline.dispatch.domain.optimizer.PlanResult;
import com.dawnline.dispatch.domain.optimizer.PlanningBudget;
import com.dawnline.dispatch.domain.optimizer.PlanningProblem;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import com.dawnline.dispatch.domain.optimizer.rule.UnassignedPenaltyRule;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 비용 상한 전략 — 결과를 <strong>손으로 계산할 수 있다</strong>.
 *
 * <p>전략이 하나도 없는 상태에서 만든 하네스는 스스로 돌아가는지 알 수 없다. 이 전략의 비용은
 * 미배정 페널티의 합이라 검산이 되고, 그래서 파이프라인·지표·리포트가 맞는지 확인된다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class UnassignAllStrategyTest {

    private static final Instant START = Instant.parse("2026-09-06T01:00:00Z");
    private static final PlanningBudget BUDGET =
            new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(3));

    private PlanningProblem problem(long seed) {
        RuleSet rules = RuleSet.of(
                List.of(new UnassignedPenaltyRule("unassigned", 900, 30_000, 20_000)), 1);
        return new DatasetGenerator(Dataset.SMALL, seed, START).generate(rules, BUDGET);
    }

    @Test
    void 아무것도_배정하지_않는다() {
        PlanResult result = new UnassignAllStrategy().plan(problem(1L));

        assertThat(result.routes()).isEmpty();
        assertThat(result.unassigned()).hasSize(500);
        assertThat(result.metrics().unassignedOrders()).isEqualTo(500);
        assertThat(result.metrics().unassignedRatio()).isEqualTo(1.0d);
    }

    @Test
    void 비용은_미배정_페널티의_합이고_손으로_검산된다() {
        PlanningProblem problem = problem(1L);

        long expected = problem.candidates().stream()
                .mapToLong(candidate -> 30_000L + 20_000L * candidate.priority())
                .sum();

        assertThat(new UnassignAllStrategy().plan(problem).totalCost()).isEqualTo(Money.krw(expected));
    }

    @Test
    void 주문마다_설명이_남는다() {
        // "왜 미배정인가" 에 답할 수 없으면 §6.3 이 설명을 요구한 이유가 사라진다.
        PlanResult result = new UnassignAllStrategy().plan(problem(2L));

        assertThat(result.explanations()).hasSize(500)
                .allSatisfy(explanation -> assertThat(explanation.ruleName())
                        .isEqualTo(UnassignAllStrategy.NAME));
    }

    @Test
    void 미배정_목록이_후보와_일대일로_맞는다() {
        PlanningProblem problem = problem(3L);

        assertThat(new UnassignAllStrategy().plan(problem).unassigned())
                .extracting(com.dawnline.dispatch.domain.optimizer.Unassigned::orderId)
                .containsExactlyElementsOf(problem.candidates().stream().map(Candidate::id).toList());
    }

    @Test
    void 결정적이다() {
        PlanningProblem problem = problem(4L);

        assertThat(new UnassignAllStrategy().plan(problem).totalCost())
                .isEqualTo(new UnassignAllStrategy().plan(problem).totalCost());
    }
}
