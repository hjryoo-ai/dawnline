package com.dawnline.benchmark;

import com.dawnline.dispatch.domain.optimizer.DispatchStrategy;
import com.dawnline.dispatch.domain.optimizer.PlanResult;
import com.dawnline.dispatch.domain.optimizer.PlanValidator;
import com.dawnline.dispatch.domain.optimizer.PlanningProblem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 전략 × 회차를 돌리고 결과를 모은다 (DESIGN.md §6.9).
 *
 * <h2>같은 문제로 돌린다</h2>
 * 전략마다 문제를 새로 만들면 비교가 아니라 두 개의 다른 측정이 된다. 문제는 한 번 만들고 전략만
 * 바꾼다 — 그래서 <strong>비용 비교가 러너 사양에 독립</strong>이고, §6.9 의 회귀 게이트가 비용만
 * 보는 근거가 여기다.
 *
 * <h2>최종 검증을 매 회차 돌린다</h2>
 * {@link PlanValidator} 가 위반을 내면 그 전략의 결과는 <em>비교할 자격이 없다</em> — 하드 룰을
 * 어기면서 싸게 나온 계획은 싼 것이 아니다. 그래서 리포트에 적기 전에 여기서 실패시킨다.
 */
public final class BenchmarkRunner {

    private final StrategyRegistry registry;
    private final PlanValidator validator = new PlanValidator();
    private final LongSupplier nanoClock;

    /**
     * @param registry  전략 레지스트리
     * @param nanoClock 경과 시간 출처. 테스트가 시간을 고정할 수 있어야 한다 (불변규칙 12)
     */
    public BenchmarkRunner(StrategyRegistry registry, LongSupplier nanoClock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /** 실제 시계로 재는 기본 러너. */
    public static BenchmarkRunner standard(StrategyRegistry registry) {
        return new BenchmarkRunner(registry, System::nanoTime);
    }

    /**
     * @param problem    같은 문제
     * @param strategies 비교할 전략 이름들
     * @param repeats    전략당 반복 횟수 (§6.9 는 5회)
     */
    public Map<String, StrategySummary> run(PlanningProblem problem, List<String> strategies,
            int repeats) {

        Objects.requireNonNull(problem, "problem");
        if (repeats < 1) {
            throw new IllegalArgumentException("반복 횟수는 1 이상이어야 합니다: " + repeats);
        }
        Map<String, StrategySummary> summaries = new LinkedHashMap<>();
        for (String strategy : strategies) {
            List<RunOutcome> runs = new ArrayList<>(repeats);
            for (int i = 0; i < repeats; i++) {
                runs.add(runOnce(problem, strategy));
            }
            summaries.put(strategy, new StrategySummary(strategy, runs));
        }
        return summaries;
    }

    private RunOutcome runOnce(PlanningProblem problem, String strategyName) {
        DispatchStrategy strategy = registry.create(strategyName);
        long start = nanoClock.getAsLong();
        PlanResult result = strategy.plan(problem);
        long elapsedMs = (nanoClock.getAsLong() - start) / 1_000_000L;

        List<PlanValidator.Violation> violations = validator.validate(problem, result);
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "%s 의 결과가 하드 룰을 어겼습니다 (%d건). 첫 위반: %s".formatted(
                            strategyName, violations.size(), violations.getFirst().feasibility()));
        }
        Map<String, Long> reasons = result.unassigned().stream().collect(
                java.util.stream.Collectors.groupingBy(
                        com.dawnline.dispatch.domain.optimizer.Unassigned::ruleName,
                        java.util.stream.Collectors.counting()));
        return new RunOutcome(result.metrics(), result.totalCost(), elapsedMs, reasons);
    }
}
