package com.dawnline.benchmark;

import com.dawnline.dispatch.domain.optimizer.PlanningBudget;
import com.dawnline.dispatch.domain.optimizer.PlanningProblem;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 벤치마크 CLI 진입점 (DESIGN.md §6.9).
 *
 * <p>Spring 컨텍스트가 없다. {@code new} 로 도메인 객체를 만들고 바로 실행한다 — 그것이 이 모듈이
 * 증명하는 사실이고, 불변규칙 5 가 존재하는 이유다.
 */
public final class BenchmarkMain {

    private BenchmarkMain() {
    }

    /**
     * @param args CLI 인자. {@link BenchmarkOptions#usage()} 참고
     */
    public static void main(String[] args) {
        StrategyRegistry registry = StrategyRegistry.standard();
        BenchmarkOptions options;
        try {
            options = BenchmarkOptions.parse(args, registry.names());
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }

        // 계획 시작 시각은 입력이다 (불변규칙 12). 벽시계를 도메인이 부르지 않는다.
        Instant startedAt = Clock.systemUTC().instant();
        Path rulesFile = options.rulesFile() != null ? options.rulesFile() : RuleSeed.locate();
        RuleSet rules = RuleSeed.load(rulesFile, 1);
        PlanningBudget budget = new PlanningBudget(options.budget(),
                // 라우트당 예산은 전체의 1/10 로 둔다. §6.7 은 "잔여 예산을 클러스터 수로 나눈다"
                // 고 했고, 그 배분은 개선 단계(Phase 4)가 한다 — 여기서는 상한만 준다.
                maxOneTenth(options.budget()));

        PlanningProblem problem = new DatasetGenerator(options.dataset(), options.seed(), startedAt)
                .generate(rules, budget);

        System.err.printf("데이터셋 %s · 주문 %d · 차량 %d · seed %d · 전략 %s · %d회%n",
                options.dataset().cliName(), problem.candidates().size(), problem.vehicles().size(),
                options.seed(), options.strategies(), options.repeats());

        Map<String, StrategySummary> summaries = BenchmarkRunner.standard(registry)
                .run(problem, options.strategies(), options.repeats());

        String report = new MarkdownReport(options.dataset(), options.seed(), options.repeats(),
                startedAt, SourceVersion.detect()).render(summaries);
        write(report, options.out());

        if (options.gate() != null) {
            // 리포트를 먼저 쓴다. 게이트가 실패해도 CI 아티팩트에는 <em>왜</em> 실패했는지가
            // 남아야 한다 — 종료 코드만 남기면 다음 사람이 다시 돌려야 한다.
            List<RegressionGate.Failure> failures =
                    new RegressionGate(options.gate()).evaluate(summaries);
            failures.forEach(failure -> System.err.println("게이트 실패: " + failure.describe()));
            if (!failures.isEmpty()) {
                System.exit(1);
                return;
            }
            System.err.printf("게이트 통과: 기준 %s 보다 비싼 전략이 없습니다%n", options.gate());
        }
    }

    private static Duration maxOneTenth(Duration total) {
        Duration tenth = total.dividedBy(10);
        return tenth.isZero() ? total : tenth;
    }

    private static void write(String report, Path out) {
        if (out == null) {
            System.out.print(report);
            return;
        }
        try {
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(out, report);
        } catch (IOException e) {
            throw new UncheckedIOException("리포트를 쓸 수 없습니다: " + out.toAbsolutePath(), e);
        }
        System.err.println("리포트: " + out.toAbsolutePath());
    }
}
