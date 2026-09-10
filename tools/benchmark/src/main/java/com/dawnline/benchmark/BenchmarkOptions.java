package com.dawnline.benchmark;

import com.dawnline.dispatch.domain.PlanMode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * CLI 인자 (CLAUDE.md 「명령어」).
 *
 * <pre>
 * ./gradlew :tools:benchmark:run --args='--dataset small --strategies baseline-nn,sweep-greedy-nn+ls'
 * </pre>
 *
 * <p>라이브러리를 쓰지 않고 손으로 읽는다 — 인자가 열 개 미만이고, 이 하나를 위해 의존을 늘리는
 * 것은 CLAUDE.md 의 "새 라이브러리 추가는 최소화" 에 어긋난다.
 *
 * @param dataset    데이터셋
 * @param strategies 비교할 전략들 (등록 순서 아님 — 적은 순서대로 표에 나온다)
 * @param repeats    전략당 반복 횟수
 * @param seed       문제 생성 seed
 * @param budget     계획 시간 예산
 * @param rulesFile  룰 시드 JSON. {@code null} 이면 {@link RuleSeed#locate()} 가 찾는다
 * @param out        리포트 출력 경로. 없으면 표준 출력
 * @param gate       회귀 게이트의 기준 전략. {@code null} 이면 게이트 없이 리포트만 낸다
 * @param mode       실행 모드 (§6.7). {@code FAST} 면 개선 단계를 생략한다 — §6.7 의
 *                   「같은 조건 fast mode ≤ 5초」를 <em>재는</em> 자리다
 * @param budgetFactor 개선 예산에 곱하는 계수 (§6.7 사다리의 아랫단). 열화가 «개선을 끄는
 *                   것»과 «덜 하는 것»으로 갈리므로, 그 둘의 대가를 나란히 재려면 이 축이
 *                   필요하다. <strong>예산이 조이지 않으면 아무것도 하지 않는다</strong>
 */
public record BenchmarkOptions(Dataset dataset, List<String> strategies, int repeats, long seed,
        Duration budget, Path rulesFile, Path out, String gate, PlanMode mode,
        double budgetFactor) {

    private static final Dataset DEFAULT_DATASET = Dataset.SMALL;
    private static final int DEFAULT_REPEATS = 5;
    private static final long DEFAULT_SEED = 20_260_905L;
    private static final Duration DEFAULT_BUDGET = Duration.ofSeconds(30);

    public BenchmarkOptions {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(mode, "mode");
        if (!(budgetFactor > 0.0d) || budgetFactor > 1.0d) {
            throw new IllegalArgumentException(
                    "개선 예산 계수는 0 초과 1 이하여야 합니다: " + budgetFactor);
        }
        strategies = List.copyOf(Objects.requireNonNull(strategies, "strategies"));
        if (strategies.isEmpty()) {
            throw new IllegalArgumentException("비교할 전략이 하나도 없습니다");
        }
        if (repeats < 1) {
            throw new IllegalArgumentException("반복 횟수는 1 이상이어야 합니다: " + repeats);
        }
        if (gate != null && !strategies.contains(gate)) {
            throw new IllegalArgumentException(
                    "게이트 기준 전략이 비교 목록에 없습니다: %s (목록: %s)".formatted(gate, strategies));
        }
        if (gate != null && strategies.size() < 2) {
            // 기준 하나만 돌면 게이트는 언제나 통과한다. 조용히 통과하는 게이트는 없는 것만
            // 못하다 — 꺼진 줄 모르고 믿게 된다.
            throw new IllegalArgumentException("게이트에는 기준 말고 비교할 전략이 필요합니다: " + strategies);
        }
    }

    /**
     * 인자를 읽는다. 알 수 없는 인자는 <strong>조용히 무시하지 않는다</strong> — 오타 난 옵션이
     * 무시되면 "기본값으로 돌았다" 는 사실이 리포트 어디에도 남지 않는다.
     *
     * @param args        명령행 인자
     * @param defaultOnes 등록된 전략 이름들 ({@code --strategies} 를 생략했을 때 쓴다)
     */
    public static BenchmarkOptions parse(String[] args, List<String> defaultOnes) {
        Dataset dataset = DEFAULT_DATASET;
        List<String> strategies = defaultOnes;
        int repeats = DEFAULT_REPEATS;
        long seed = DEFAULT_SEED;
        Duration budget = DEFAULT_BUDGET;
        Path rules = null;
        Path out = null;
        String gate = null;
        PlanMode mode = PlanMode.FULL;
        double budgetFactor = 1.0d;

        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            switch (flag) {
                case "--dataset" -> dataset = Dataset.fromCli(value(args, ++i, flag));
                case "--strategies" -> strategies = List.of(value(args, ++i, flag).split(","));
                case "--repeats" -> repeats = Integer.parseInt(value(args, ++i, flag));
                case "--seed" -> seed = Long.parseLong(value(args, ++i, flag));
                case "--budget-seconds" ->
                        budget = Duration.ofSeconds(Long.parseLong(value(args, ++i, flag)));
                case "--rules" -> rules = Path.of(value(args, ++i, flag));
                case "--out" -> out = Path.of(value(args, ++i, flag));
                case "--gate" -> gate = value(args, ++i, flag);
                case "--mode" -> mode = PlanMode.valueOf(
                        value(args, ++i, flag).toUpperCase(java.util.Locale.ROOT));
                case "--budget-factor" ->
                        budgetFactor = Double.parseDouble(value(args, ++i, flag));
                default -> throw new IllegalArgumentException(
                        "알 수 없는 인자: %s%n%s".formatted(flag, usage()));
            }
        }
        return new BenchmarkOptions(dataset, strategies, repeats, seed, budget, rules, out,
                gate, mode, budgetFactor);
    }

    /** 사용법. */
    public static String usage() {
        return """
                사용법: benchmark [옵션]
                  --dataset <small|medium|large|peak>  기본 small
                  --strategies <a,b,c>                 기본: 등록된 전략 전부
                  --repeats <n>                        기본 5 (§6.9)
                  --seed <n>                           기본 20260905
                  --budget-seconds <n>                 기본 30 (§6.7)
                  --rules <path>                       기본: 위로 올라가며 찾은 contracts/seed/dispatch-rules.json
                  --out <path>                         없으면 표준 출력
                  --gate <strategy>                    이 전략보다 비싼 전략이 있으면 종료 코드 1 (§6.9)
                  --mode <full|fast>                   기본 full. fast 는 개선 단계를 생략한다 (§6.7)
                  --budget-factor <0~1>                기본 1.0. 개선 예산에 곱한다 (§6.7 사다리)""";
    }

    private static String value(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException("%s 에 값이 없습니다%n%s".formatted(flag, usage()));
        }
        return args[index];
    }
}
