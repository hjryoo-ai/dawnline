package com.dawnline.sim.driver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * 여정들이 함께 채우는 집계. 라우트마다 스레드가 다르므로 전부 원자적이다.
 *
 * <p>세는 것과 보고하는 것을 나눈 이유는 {@code ScenarioReport} 와 같다 — 「몇 건 보냈다」만으로는
 * 다음에 볼 곳을 못 정한다. 무엇이 왜 실패했는지가 있어야 한다.
 */
public final class DriverTally {

    private final AtomicLong routes = new AtomicLong();
    private final AtomicLong revisions = new AtomicLong();
    private final AtomicLong staleRevisions = new AtomicLong();
    private final AtomicLong completedRoutes = new AtomicLong();
    private final AtomicLong abandonedRoutes = new AtomicLong();
    private final AtomicLong scans = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final Map<String, LongAdder> outcomes = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> failures = new ConcurrentHashMap<>();

    /** 처음 보는 라우트다. */
    public void routeStarted() {
        routes.incrementAndGet();
    }

    /** 개정을 받아들였다 (최초 확정 포함). */
    public void revisionApplied() {
        revisions.incrementAndGet();
    }

    /**
     * 이미 본 개정이라 버렸다 — tracking 의 {@code dawnline_event_stale_total} 과 같은 자리다.
     * 거부가 아니라 <em>설계된 동작</em>이므로 실패로 세지 않는다.
     */
    public void revisionStale() {
        staleRevisions.incrementAndGet();
    }

    /** 라우트를 끝까지 돌았다. */
    public void routeCompleted() {
        completedRoutes.incrementAndGet();
    }

    /** 라우트를 포기했다 ({@link DriverTrip} 의 404 재시도 상한). */
    public void routeAbandoned() {
        abandonedRoutes.incrementAndGet();
    }

    /** 스캔 하나를 보냈다 (재시도는 별도로 센다). */
    public void scanSent() {
        scans.incrementAndGet();
    }

    /** 404 를 받아 다시 보냈다. */
    public void scanRetried() {
        retries.incrementAndGet();
    }

    /**
     * 스캔 응답 하나를 집계한다.
     *
     * @param response 응답
     */
    public void record(ScanClient.Response response) {
        if (response.isAccepted()) {
            response.outcomes().forEach((outcome, count) -> add(outcomes, outcome, count));
            return;
        }
        add(failures, describe(response), 1);
    }

    /** 코드가 없으면 status 로 적는다 — 「무엇 때문인지 모르겠다」도 정보다. */
    private static String describe(ScanClient.Response response) {
        if (response.status() == 0) {
            return "transport:" + response.failure();
        }
        String code = response.problemCode();
        return code == null || code.isBlank() ? "status:" + response.status() : code;
    }

    private static void add(Map<String, LongAdder> counts, String key, int amount) {
        counts.computeIfAbsent(key, ignored -> new LongAdder()).add(amount);
    }

    /**
     * 지금까지의 집계를 값으로 고정한다.
     *
     * @param scenario      시나리오 이름
     * @param expectedRoutes 기대한 라우트 수
     */
    public DriverReport snapshot(String scenario, int expectedRoutes) {
        return new DriverReport(scenario, expectedRoutes, routes.get(), revisions.get(),
                staleRevisions.get(), completedRoutes.get(), abandonedRoutes.get(),
                scans.get(), retries.get(), copy(outcomes), copy(failures));
    }

    private static Map<String, Long> copy(Map<String, LongAdder> counts) {
        return counts.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().sum()));
    }
}
