package com.dawnline.benchmark;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * §6.9 리포트 — 표와 환경.
 *
 * <p><strong>환경 없는 수치는 나중에 비교 대상이 되지 못한다.</strong> Phase 1 k6 와 Phase 2
 * EXPLAIN 리포트가 같은 규칙을 따랐고, 그 덕에 "그때 그 수치는 어떤 기계였나" 를 되물을 필요가 없다.
 *
 * <h2>리포트의 신원은 커밋 · seed · 전략 이름 셋이다</h2>
 * 세 값을 헤더 첫 줄에 박는다(2026-09-05, §6.9). 동결({@code BaselineFrozenTest})이 지키는 것은
 * <strong>같은 실행 안의 비교</strong>이지 절대 수치가 아니다 — 두 전략이 공유하는
 * {@code StopMerger}·{@code CostModel}·거리 함수가 바뀌면 같은 전략이 다른 수를 낸다. 그래서
 * <strong>리포트끼리 절대 수치를 비교하기 전에 커밋이 같은지 먼저 봐야 하고</strong>, 그 규칙은
 * 헤더에 값이 있어야 지킬 수 있다. 자세한 것은 {@link SourceVersion}.
 */
public final class MarkdownReport {

    private final Dataset dataset;
    private final long seed;
    private final int repeats;
    private final Instant generatedAt;
    private final SourceVersion source;

    /**
     * @param dataset     데이터셋
     * @param seed        문제 생성 seed
     * @param repeats     전략당 반복 횟수
     * @param generatedAt 생성 시각
     * @param source      이 리포트를 낸 소스의 커밋 (§6.9)
     */
    public MarkdownReport(Dataset dataset, long seed, int repeats, Instant generatedAt,
            SourceVersion source) {
        this.dataset = Objects.requireNonNull(dataset, "dataset");
        this.seed = seed;
        this.repeats = repeats;
        this.generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * @param summaries 전략별 요약 (등록 순서)
     */
    public String render(Map<String, StrategySummary> summaries) {
        StringBuilder out = new StringBuilder();
        out.append("# 전략 비교 — ").append(dataset.cliName()).append("\n\n");
        out.append("생성 ").append(generatedAt).append(" · ").append(source.describe())
                .append(" · 데이터셋 **")
                .append(dataset.cliName()).append("**(주문 ").append(dataset.orders())
                .append(" · 차량 ").append(dataset.vehicles()).append(") · seed `")
                .append(seed).append("` · 전략 ")
                .append(summaries.keySet().stream().map(name -> "`" + name + "`")
                        .collect(java.util.stream.Collectors.joining(", ")))
                .append(" · 전략당 ").append(repeats).append("회\n\n");
        out.append("> 이 셋(커밋 · seed · 전략 이름)이 리포트의 신원이다. **다른 리포트의 절대 수치와\n");
        out.append("> 비교하기 전에 커밋이 같은지 먼저 본다** — 동결되는 것은 `baseline-nn` 클래스이지\n");
        out.append("> 그것이 쓰는 `StopMerger`·`CostModel`·거리 함수가 아니다 (§6.9).\n\n");

        out.append("| 전략 | 총비용(중앙값) | 미배정 | 주된 사유 | 차량 | 총거리 | 계획시간 p50 | p95 | 지각 stop | 평균 지각(분) |\n");
        out.append("|---|---:|---:|---|---:|---:|---:|---:|---:|---:|\n");
        summaries.values().forEach(summary -> out
                .append("| `").append(summary.strategy()).append("` | ")
                .append(String.format("%,d", summary.medianCostKrw())).append(" | ")
                .append(summary.medianUnassigned()).append(" | ")
                .append(summary.dominantUnassignedReason()).append(" | ")
                .append(summary.medianVehiclesUsed()).append(" | ")
                .append(String.format("%,d m", summary.medianDistanceM())).append(" | ")
                .append(summary.medianDurationMs()).append(" ms | ")
                .append(summary.p95DurationMs()).append(" ms | ")
                .append(summary.medianLateStops()).append(" | ")
                .append(String.format("%.1f", summary.medianAverageLateMinutes())).append(" |\n"));

        out.append("\n### 비용 분해 (§6.1 목적함수의 항)\n\n");
        out.append("| 전략 | 고정비 | 거리비 | 시간비 | 소프트 페널티 | 미배정 페널티 | 합 | 차량 | 미배정 |\n");
        out.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        summaries.values().forEach(summary -> {
            CostBreakdown breakdown = summary.breakdown();
            out.append("| `").append(summary.strategy()).append("` | ")
                    .append(String.format("%,d", breakdown.fixedKrw())).append(" | ")
                    .append(String.format("%,d", breakdown.distanceKrw())).append(" | ")
                    .append(String.format("%,d", breakdown.timeKrw())).append(" | ")
                    .append(String.format("%,d", breakdown.softPenaltyKrw())).append(" | ")
                    .append(String.format("%,d", breakdown.unassignedKrw())).append(" | ")
                    .append(String.format("%,d", breakdown.totalKrw())).append(" | ")
                    .append(breakdown.vehiclesUsed()).append(" | ")
                    .append(breakdown.unassignedOrders()).append(" |\n");
        });

        out.append("\n비용은 §6.1 의 목적함수다 — 차량 비용 + 미배정 페널티 + 소프트 룰 페널티.\n");
        out.append("**계획 시간은 기록만 하고 게이트에 넣지 않는다**(§6.9): 두 전략을 같은 실행 안에서\n");
        out.append("돌리므로 비용 비교는 러너 사양에 독립이지만, 시간은 러너에 따라 흔들린다.\n\n");

        out.append("## 환경\n\n");
        out.append("| 항목 | 값 |\n|---|---|\n");
        row(out, "OS", "%s %s (%s)".formatted(System.getProperty("os.name"),
                System.getProperty("os.version"), System.getProperty("os.arch")));
        row(out, "JVM", "%s %s".formatted(System.getProperty("java.vm.name"),
                System.getProperty("java.version")));
        row(out, "가용 프로세서", String.valueOf(Runtime.getRuntime().availableProcessors()));
        row(out, "최대 힙", "%,d MB".formatted(Runtime.getRuntime().maxMemory() / (1024 * 1024)));
        row(out, "GC", String.join(", ", ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(java.lang.management.GarbageCollectorMXBean::getName).toList()));
        return out.toString();
    }

    private static void row(StringBuilder out, String label, String value) {
        out.append("| ").append(label).append(" | ").append(value).append(" |\n");
    }
}
