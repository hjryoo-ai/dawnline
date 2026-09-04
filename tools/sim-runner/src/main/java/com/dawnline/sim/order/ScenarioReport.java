package com.dawnline.sim.order;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 시나리오 실행 결과.
 *
 * <p>"200건 보냈다" 만으로는 아무것도 알 수 없다. 실패한 건이 <em>왜</em> 실패했는지가 없으면
 * 다음에 볼 곳을 못 정한다. 그래서 4xx 를 Problem Details 의 {@code code} 별로 센다.
 *
 * @param scenario        시나리오 이름
 * @param requested       보낸 주문 수
 * @param accepted        201
 * @param replayed        200 (멱등 재생). 이 시나리오에서는 0 이어야 한다 — 키가 매번 다르다
 * @param clientErrors    4xx
 * @param serverErrors    5xx
 * @param transportErrors 응답을 받지 못한 건
 * @param problemCodes    코드별 4xx/5xx 건수
 * @param latency         지연 요약
 * @param elapsed         전체 소요 시간
 */
public record ScenarioReport(
        String scenario,
        int requested,
        int accepted,
        int replayed,
        int clientErrors,
        int serverErrors,
        int transportErrors,
        Map<String, Integer> problemCodes,
        Latency latency,
        Duration elapsed) {

    public ScenarioReport {
        problemCodes = Map.copyOf(problemCodes);
    }

    /** 보낸 것이 모두 접수되었는가. 이 값이 종료 코드가 된다. */
    public boolean isSuccess() {
        return accepted == requested;
    }

    /**
     * 지연 요약(ms).
     *
     * @param p50 중앙값
     * @param p95 95 백분위
     * @param p99 99 백분위
     * @param max 최댓값
     */
    public record Latency(double p50, double p95, double p99, double max) {

        private static final double NANOS_PER_MILLI = 1_000_000.0;

        /** 빈 표본. */
        public static Latency empty() {
            return new Latency(0, 0, 0, 0);
        }

        /**
         * 표본에서 만든다. 표본이 200건뿐이라 정렬해서 직접 고른다 — 근사할 이유가 없다.
         *
         * @param nanos 지연 표본(ns). 정렬해서 쓰므로 배열은 복사한다
         */
        public static Latency of(long[] nanos) {
            if (nanos.length == 0) {
                return empty();
            }
            long[] sorted = nanos.clone();
            Arrays.sort(sorted);
            return new Latency(
                    percentile(sorted, 0.50),
                    percentile(sorted, 0.95),
                    percentile(sorted, 0.99),
                    sorted[sorted.length - 1] / NANOS_PER_MILLI);
        }

        private static double percentile(long[] sorted, double q) {
            int index = (int) Math.ceil(q * sorted.length) - 1;
            return sorted[Math.clamp(index, 0, sorted.length - 1)] / NANOS_PER_MILLI;
        }
    }

    /** 사람이 읽는 요약. 실행 로그의 마지막 줄이자, 문서에 붙일 수 있는 표다. */
    public String toMarkdown() {
        Map<String, Integer> sortedCodes = new TreeMap<>(problemCodes);
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("시나리오", scenario);
        rows.put("보낸 주문", String.valueOf(requested));
        rows.put("접수(201)", "%d (%.1f%%)".formatted(accepted, ratio(accepted)));
        rows.put("멱등 재생(200)", String.valueOf(replayed));
        rows.put("4xx", String.valueOf(clientErrors));
        rows.put("5xx", String.valueOf(serverErrors));
        rows.put("응답 없음", String.valueOf(transportErrors));
        rows.put("p50 / p95 / p99 / max",
                "%.1f / %.1f / %.1f / %.1f ms".formatted(latency.p50(), latency.p95(), latency.p99(), latency.max()));
        rows.put("소요", "%.1f 초".formatted(elapsed.toMillis() / 1000.0));

        StringBuilder out = new StringBuilder("| 항목 | 값 |\n|---|---|\n");
        rows.forEach((key, value) -> out.append("| ").append(key).append(" | ").append(value).append(" |\n"));
        sortedCodes.forEach((code, count) ->
                out.append("| — ").append(code).append(" | ").append(count).append(" |\n"));
        return out.toString();
    }

    private double ratio(int value) {
        return requested == 0 ? 0.0 : value * 100.0 / requested;
    }
}
