package com.dawnline.benchmark;

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
 * <p>라이브러리를 쓰지 않고 손으로 읽는다 — 인자가 다섯 개이고, 이 하나를 위해 의존을 늘리는 것은
 * CLAUDE.md 의 "새 라이브러리 추가는 최소화" 에 어긋난다.
 *
 * @param dataset    데이터셋
 * @param strategies 비교할 전략들 (등록 순서 아님 — 적은 순서대로 표에 나온다)
 * @param repeats    전략당 반복 횟수
 * @param seed       문제 생성 seed
 * @param budget     계획 시간 예산
 * @param rulesFile  룰 시드 JSON. {@code null} 이면 {@link RuleSeed#locate()} 가 찾는다
 * @param out        리포트 출력 경로. 없으면 표준 출력
 */
public record BenchmarkOptions(Dataset dataset, List<String> strategies, int repeats, long seed,
        Duration budget, Path rulesFile, Path out) {

    private static final Dataset DEFAULT_DATASET = Dataset.SMALL;
    private static final int DEFAULT_REPEATS = 5;
    private static final long DEFAULT_SEED = 20_260_905L;
    private static final Duration DEFAULT_BUDGET = Duration.ofSeconds(30);

    public BenchmarkOptions {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(budget, "budget");
        strategies = List.copyOf(Objects.requireNonNull(strategies, "strategies"));
        if (strategies.isEmpty()) {
            throw new IllegalArgumentException("비교할 전략이 하나도 없습니다");
        }
        if (repeats < 1) {
            throw new IllegalArgumentException("반복 횟수는 1 이상이어야 합니다: " + repeats);
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
                default -> throw new IllegalArgumentException(
                        "알 수 없는 인자: %s%n%s".formatted(flag, usage()));
            }
        }
        return new BenchmarkOptions(dataset, strategies, repeats, seed, budget, rules, out);
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
                  --out <path>                         없으면 표준 출력""";
    }

    private static String value(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException("%s 에 값이 없습니다%n%s".formatted(flag, usage()));
        }
        return args[index];
    }
}
