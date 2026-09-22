package com.dawnline.sim.driver;

import com.dawnline.sim.order.Sleeper;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 기사들 — 라우트마다 {@link DriverTrip} 하나, 가상 스레드 하나.
 *
 * <h2>중복 소비를 여기서 거른다</h2>
 * 이 도구에는 {@code processed_events} 가 없다 (불변규칙 2 의 예외, {@code package-info} 참고).
 * 대신 라우트마다 <strong>본 개정의 최댓값</strong>을 들고, 그보다 낮거나 같은 이벤트를 버린다 —
 * tracking 의 {@code route_revisions} 와 같은 모양이고, 재전달(같은 개정)과 순서 역전(낮은 개정)을
 * 함께 흡수한다. 예외가 성립하는 이유는 「도구라서」가 아니라 <strong>하류가 멱등이라서</strong>다:
 * 이 거름망이 새더라도 tracking 이 {@code STALE} 로 받는다.
 */
public final class DriverFleet implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DriverFleet.class);

    private final DriverSimulator simulator;
    private final ScanClient scans;
    private final DoubleSupplier speed;
    private final Sleeper sleeper;
    private final LongSupplier nanoTime;
    private final long retryLimitNanos;
    private final DriverTally tally;
    private final ExecutorService drivers = Executors.newVirtualThreadPerTaskExecutor();
    private final CountDownLatch finished;

    /** routeId → 이 라우트를 도는 기사. 최초 확정에서 만든다. */
    private final Map<UUID, DriverTrip> trips = new HashMap<>();

    /** routeId → 본 개정의 최댓값. */
    private final Map<UUID, Integer> seenRevisions = new HashMap<>();

    /**
     * @param expectedRoutes  기다릴 라우트 수
     * @param simulator       순수 시뮬레이터
     * @param scans           스캔 API
     * @param speed           배속. 여정마다 {@link TripPacer} 를 새로 만들 때 읽는다
     * @param sleeper         재시도 대기
     * @param nanoTime        단조 시계
     * @param retryLimitNanos 404 재시도 상한
     * @param tally           집계
     */
    public DriverFleet(int expectedRoutes, DriverSimulator simulator, ScanClient scans, DoubleSupplier speed,
            Sleeper sleeper, LongSupplier nanoTime, long retryLimitNanos, DriverTally tally) {
        this.simulator = Objects.requireNonNull(simulator, "simulator");
        this.scans = Objects.requireNonNull(scans, "scans");
        this.speed = Objects.requireNonNull(speed, "speed");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.retryLimitNanos = retryLimitNanos;
        this.tally = Objects.requireNonNull(tally, "tally");
        this.finished = new CountDownLatch(Math.max(0, expectedRoutes));
    }

    /**
     * 개정 하나를 받는다. 처음 보는 라우트면 기사를 하나 붙인다.
     *
     * @param route 개정
     */
    public synchronized void assign(AssignedRoute route) {
        Integer seen = seenRevisions.get(route.routeId());
        if (seen != null && route.revision() <= seen) {
            tally.revisionStale();
            log.debug("이미 본 개정이라 버렸다. routeId={}, revision={} (본 것={})",
                    route.routeId(), route.revision(), seen);
            return;
        }
        seenRevisions.put(route.routeId(), route.revision());
        tally.revisionApplied();

        DriverTrip trip = trips.get(route.routeId());
        if (trip == null) {
            trip = new DriverTrip(route.routeId(), simulator, scans,
                    new TripPacer(speed.getAsDouble(), nanoTime), sleeper, nanoTime, retryLimitNanos, tally);
            trips.put(route.routeId(), trip);
            tally.routeStarted();
            DriverTrip started = trip;
            drivers.execute(() -> {
                try {
                    started.run();
                } finally {
                    finished.countDown();
                }
            });
        }
        trip.revise(route);
    }

    /**
     * 기다린 만큼의 라우트가 끝날 때까지 기다린다.
     *
     * @param timeout 상한
     * @return 다 끝났으면 {@code true}. 시간이 다 되었으면 {@code false}
     * @throws InterruptedException 대기 중 인터럽트
     */
    public boolean awaitRoutes(Duration timeout) throws InterruptedException {
        return finished.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public void close() {
        drivers.shutdownNow();
    }
}
