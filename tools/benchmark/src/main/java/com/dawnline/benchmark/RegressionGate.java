package com.dawnline.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 회귀 게이트 (DESIGN.md §6.9, IMPLEMENTATION_PLAN Phase 3-4).
 *
 * <p>규칙은 하나다 — <strong>기준 전략보다 비싼 전략이 있으면 실패</strong>.
 *
 * <h2>비용만 본다</h2>
 * 두 전략을 <em>같은 실행 안에서</em> 돌리므로 비용 비교는 러너 사양에 독립이다. 계획 시간은
 * 그렇지 않다 — CI 러너의 부하에 따라 배로 흔들린다. 시간을 게이트에 넣으면 코드와 무관하게
 * 빨개지고, 그렇게 빨개지는 게이트는 결국 꺼진다. 시간은 리포트에 기록만 한다.
 *
 * <h2>기준은 중앙값이다</h2>
 * {@link StrategySummary#medianCostKrw()} 를 쓴다. 한 회차의 GC 가 평균을 끌고 가는 것은
 * 계획 시간에서 본 일이고, 비용은 결정적이라 회차 간에 흔들리지 않지만 — 그래도 같은 통계량을
 * 쓴다. 리포트의 표와 게이트가 다른 수를 보면 "리포트는 이겼다는데 게이트는 실패" 가 된다.
 *
 * <h2>기준 전략은 동결된다</h2>
 * 게이트가 켜진 순간 {@code baseline-nn} 은 <strong>코드 수준으로</strong> 동결이다. 베이스라인이
 * 좋아지면 그때까지의 비교가 전부 무효가 되기 때문이고, 그 동결은
 * {@code BaselineFrozenTest} 가 지킨다. 바꿔야 하면 {@code docs/benchmarks/} 에 재기준
 * 기록을 남기고 수치를 다시 낸다.
 *
 * @param baseline 기준 전략 이름
 */
public record RegressionGate(String baseline) {

    public RegressionGate {
        Objects.requireNonNull(baseline, "baseline");
    }

    /**
     * 기준보다 비싼 전략들. 비어 있으면 통과다.
     *
     * @param summaries 전략별 요약
     */
    public List<Failure> evaluate(Map<String, StrategySummary> summaries) {
        Objects.requireNonNull(summaries, "summaries");
        StrategySummary reference = summaries.get(baseline);
        if (reference == null) {
            throw new IllegalArgumentException(
                    "기준 전략의 결과가 없습니다: %s (돈 전략: %s)".formatted(baseline, summaries.keySet()));
        }
        long referenceCost = reference.medianCostKrw();

        List<Failure> failures = new ArrayList<>();
        for (StrategySummary summary : summaries.values()) {
            if (summary.strategy().equals(baseline)) {
                continue;
            }
            if (summary.medianCostKrw() > referenceCost) {
                failures.add(new Failure(summary.strategy(), referenceCost,
                        summary.medianCostKrw()));
            }
        }
        return List.copyOf(failures);
    }

    /**
     * 기준보다 비싼 전략 하나.
     *
     * @param strategy    그 전략
     * @param baselineKrw 기준 비용(중앙값)
     * @param strategyKrw 그 전략의 비용(중앙값)
     */
    public record Failure(String strategy, long baselineKrw, long strategyKrw) {

        /** 기준 대비 초과율(%). */
        public double lossPercent() {
            return baselineKrw == 0 ? 0d : (strategyKrw - baselineKrw) * 100d / baselineKrw;
        }

        /** 사람이 읽는 한 줄. */
        public String describe() {
            return "%s 가 기준보다 비쌉니다: %,d > %,d (+%.1f%%)"
                    .formatted(strategy, strategyKrw, baselineKrw, lossPercent());
        }
    }
}
