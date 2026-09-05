package com.dawnline.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.Money;
import com.dawnline.dispatch.domain.optimizer.DispatchStrategy;
import com.dawnline.dispatch.domain.optimizer.PlanMetrics;
import com.dawnline.dispatch.domain.optimizer.PlanResult;
import com.dawnline.dispatch.domain.optimizer.PlanningBudget;
import com.dawnline.dispatch.domain.optimizer.PlanningProblem;
import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import com.dawnline.dispatch.domain.optimizer.PlannedStop;
import com.dawnline.dispatch.domain.optimizer.RouteState;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.rule.DispatchRules;
import com.dawnline.dispatch.domain.optimizer.rule.RuleDefinition;
import com.dawnline.dispatch.domain.optimizer.rule.RuleSeverity;
import com.dawnline.dispatch.domain.optimizer.rule.RuleType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class BenchmarkRunnerTest {

    private static final Instant START = Instant.parse("2026-09-06T01:00:00Z");
    private static final PlanningBudget BUDGET =
            new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(3));

    private PlanningProblem problem(RuleSet rules) {
        return new DatasetGenerator(Dataset.SMALL, 1L, START).generate(rules, BUDGET);
    }

    @Test
    void 전략당_요청한_횟수만큼_돈다() {
        StrategyRegistry registry = StrategyRegistry.standard();

        Map<String, StrategySummary> summaries = BenchmarkRunner.standard(registry)
                .run(problem(RuleSet.empty()), List.of(UnassignAllStrategy.NAME), 4);

        assertThat(summaries.get(UnassignAllStrategy.NAME).runs()).hasSize(4);
    }

    @Test
    void 회차마다_전략을_새로_만든다() {
        // 전략이 상태를 들면 두 번째 회차가 더 빨라 보인다. 그건 측정이 아니라 캐시다.
        AtomicInteger created = new AtomicInteger();
        StrategyRegistry registry = new StrategyRegistry();
        registry.register("counting", () -> {
            created.incrementAndGet();
            return new UnassignAllStrategy();
        });

        BenchmarkRunner.standard(registry).run(problem(RuleSet.empty()), List.of("counting"), 3);

        assertThat(created).hasValue(3);
    }

    @Test
    void 시간은_주입된_시계로_잰다() {
        // 실제 시계로 재면 "1 ms" 인지 "3 ms" 인지가 러너에 따라 흔들려 어설션이 불안정해진다.
        AtomicLong nanos = new AtomicLong();
        BenchmarkRunner runner = new BenchmarkRunner(StrategyRegistry.standard(),
                () -> nanos.getAndAdd(7_000_000L));

        Map<String, StrategySummary> summaries =
                runner.run(problem(RuleSet.empty()), List.of(UnassignAllStrategy.NAME), 1);

        assertThat(summaries.get(UnassignAllStrategy.NAME).medianDurationMs()).isEqualTo(7L);
    }

    @Test
    void 하드_룰을_어긴_결과는_비교_대상이_아니다() {
        // 룰을 어기면서 싸게 나온 계획은 싼 것이 아니다. 리포트에 적기 전에 실패시킨다.
        RuleSet rules = DispatchRules.ruleSet(List.of(new RuleDefinition("max-stops",
                RuleType.MAX_STOPS_PER_ROUTE, RuleSeverity.HARD, 20, Map.of("max", 1))), 1);
        PlanningProblem problem = problem(rules);

        StrategyRegistry registry = new StrategyRegistry();
        registry.register("cheater", () -> new OverstuffedStrategy());

        assertThatThrownBy(() -> BenchmarkRunner.standard(registry)
                .run(problem, List.of("cheater"), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("하드 룰");
    }

    @Test
    void 반복이_0_이면_거부한다() {
        assertThatThrownBy(() -> BenchmarkRunner.standard(StrategyRegistry.standard())
                .run(problem(RuleSet.empty()), List.of(UnassignAllStrategy.NAME), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 알_수_없는_전략은_등록된_이름과_함께_실패한다() {
        assertThatThrownBy(() -> BenchmarkRunner.standard(StrategyRegistry.standard())
                .run(problem(RuleSet.empty()), List.of("nope"), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(UnassignAllStrategy.NAME);
    }

    /** 상한을 무시하고 stop 을 몰아넣는 전략. {@link com.dawnline.dispatch.domain.optimizer.PlanValidator} 가 잡아야 한다. */
    private static final class OverstuffedStrategy implements DispatchStrategy {

        @Override
        public String name() {
            return "cheater";
        }

        @Override
        public PlanResult plan(PlanningProblem problem) {
            RouteState state = RouteState.empty(problem.vehicles().getFirst(), problem.depot(),
                    problem.distance(), problem.startedAt());
            for (int i = 0; i < 3; i++) {
                state = state.append(Stop.of(problem.candidates().get(i)));
            }
            List<PlannedStop> stops = state.stops();
            PlannedRoute route = new PlannedRoute(problem.vehicles().getFirst().id(), stops,
                    state.distanceWithReturn(), state.durationWithReturn(), Money.ZERO);
            return new PlanResult(List.of(route), List.of(), Money.ZERO,
                    new PlanMetrics(1, 3, 0, 1, state.distanceWithReturn(), 0, 0, 0, 0), List.of());
        }
    }
}
