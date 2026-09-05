package com.dawnline.benchmark;

import java.util.List;
import java.util.Objects;

/**
 * 한 전략의 반복 측정 요약 (DESIGN.md §6.9 — 중앙값·p95).
 *
 * <p>평균이 아니라 <strong>중앙값</strong>인 이유: 한 회차의 GC 나 JIT 워밍업이 평균을 끌고 간다.
 * Phase 1 k6 에서 콜드 한 번이 전체 평균을 4초로 만든 것과 같은 종류의 왜곡이다.
 */
public record StrategySummary(String strategy, List<RunOutcome> runs) {

    public StrategySummary {
        Objects.requireNonNull(strategy, "strategy");
        runs = List.copyOf(Objects.requireNonNull(runs, "runs"));
        if (runs.isEmpty()) {
            throw new IllegalArgumentException("회차가 하나도 없습니다: " + strategy);
        }
    }

    /** 총비용 중앙값 (원). 회귀 게이트가 보는 값이다. */
    public long medianCostKrw() {
        return median(runs.stream().mapToLong(run -> run.totalCost().krw()).toArray());
    }

    /** 계획 시간 중앙값(ms). */
    public long medianDurationMs() {
        return median(runs.stream().mapToLong(RunOutcome::durationMs).toArray());
    }

    /** 계획 시간 p95(ms). */
    public long p95DurationMs() {
        return percentile(runs.stream().mapToLong(RunOutcome::durationMs).toArray(), 95);
    }

    /** 총 이동 거리 중앙값(m). */
    public long medianDistanceM() {
        return median(runs.stream().mapToLong(run -> run.metrics().totalDistanceM()).toArray());
    }

    /** 미배정 주문 수 중앙값. */
    public long medianUnassigned() {
        return median(runs.stream().mapToLong(run -> run.metrics().unassignedOrders()).toArray());
    }

    /** 쓴 차량 수 중앙값. */
    public long medianVehiclesUsed() {
        return median(runs.stream().mapToLong(run -> run.metrics().vehiclesUsed()).toArray());
    }

    /** 지각 stop 수 중앙값. */
    public long medianLateStops() {
        return median(runs.stream().mapToLong(run -> run.metrics().lateStops()).toArray());
    }

    /** 지각 stop 의 평균 지각 분 (회차 중앙값). */
    public double medianAverageLateMinutes() {
        double[] values = runs.stream().mapToDouble(run -> run.metrics().averageLateMinutes())
                .sorted().toArray();
        return values[values.length / 2];
    }

    private static long median(long[] values) {
        return percentile(values, 50);
    }

    /**
     * 최근접 순위(nearest-rank) 백분위. 회차가 5회쯤이라 보간은 뜻이 없다 — 없는 값을 만들지 않고
     * 실제로 관측된 값 하나를 고른다.
     */
    private static long percentile(long[] values, int percentile) {
        long[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        int rank = (int) Math.ceil(percentile / 100.0d * sorted.length);
        return sorted[Math.min(Math.max(rank, 1), sorted.length) - 1];
    }
}
