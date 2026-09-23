package com.dawnline.sim.driver;

import com.dawnline.sim.order.Sleeper;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 라우트 하나를 도는 기사 한 명. 스레드 하나가 이 객체 하나를 돌린다.
 *
 * <h2>개정은 대기를 깨운다</h2>
 * 다음 스캔까지의 대기를 {@code sleep} 이 아니라 개정 큐의 {@link BlockingQueue#poll} 로 한다.
 * 재계획이 오면 그 자리에서 대기가 끝나고 남은 계획을 다시 세운다 — 인터럽트로 깨우는 방법도
 * 있지만, 그러면 「깨운 이유가 개정인가 종료인가」를 인터럽트 플래그 하나로 구별해야 한다.
 * 큐는 값을 함께 가져오므로 그 질문이 없다.
 *
 * <h2>404 재시도에는 상한이 있다</h2>
 * 404 의 원인은 경합이다 — tracking 과 이 도구가 같은 {@code route.assigned} 를 <strong>서로 다른
 * 컨슈머 그룹</strong>으로 읽으므로, 이 도구가 먼저 읽으면 tracking 에는 아직 그 라우트가 없다.
 * 그래서 다시 보내면 된다. 다만 <strong>조용히 무한 재시도하는 도구는 시나리오 결과를
 * 오염시킨다</strong> — tracking 이 아예 그 이벤트를 못 받은 경우와 늦게 받은 경우가 구별되지
 * 않고, 실행은 끝나지 않는다. 상한을 넘기면 그 라우트를 포기하고 로그와 카운터로 말한다.
 */
public final class DriverTrip implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(DriverTrip.class);

    /** 404 재시도 간격. 상한에 비해 충분히 짧아 여러 번 시도된다. */
    private static final long RETRY_INTERVAL_NANOS = 1_000_000_000L;

    private final UUID routeId;
    private final DriverSimulator simulator;
    private final ScanClient scans;
    private final TripPacer pacer;
    private final Sleeper sleeper;
    private final LongSupplier nanoTime;
    private final long retryLimitNanos;
    private final DriverTally tally;

    /** 아직 반영하지 않은 개정. 최초 확정도 여기로 들어온다. */
    private final BlockingQueue<AssignedRoute> revisions = new LinkedBlockingQueue<>();

    private TripProgress progress = TripProgress.start();
    private boolean abandoned;

    /**
     * @param routeId         라우트 id
     * @param simulator       순수 시뮬레이터
     * @param scans           스캔 API
     * @param pacer           배속. 여정당 하나여야 한다 (앵커를 들고 있다)
     * @param sleeper         재시도 대기
     * @param nanoTime        단조 시계
     * @param retryLimitNanos 404 재시도 상한
     * @param tally           집계
     */
    public DriverTrip(UUID routeId, DriverSimulator simulator, ScanClient scans, TripPacer pacer,
            Sleeper sleeper, LongSupplier nanoTime, long retryLimitNanos, DriverTally tally) {
        this.routeId = Objects.requireNonNull(routeId, "routeId");
        this.simulator = Objects.requireNonNull(simulator, "simulator");
        this.scans = Objects.requireNonNull(scans, "scans");
        this.pacer = Objects.requireNonNull(pacer, "pacer");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.retryLimitNanos = retryLimitNanos;
        this.tally = Objects.requireNonNull(tally, "tally");
    }

    /**
     * 개정 하나를 이 여정에 건넨다. 대기 중이면 그 자리에서 깨어난다.
     *
     * @param route 새 개정 (최초 확정 포함)
     */
    public void revise(AssignedRoute route) {
        revisions.add(route);
    }

    @Override
    public void run() {
        try {
            AssignedRoute route = revisions.take();
            while (route != null) {
                route = drive(route);
            }
            if (!abandoned) {
                tally.routeCompleted();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException exception) {
            // 라우트 하나가 터져도 다른 기사는 계속 돈다. 삼키지 않고 이름을 남긴다.
            log.warn("여정이 중단되었다. routeId={}, 원인={}", routeId, exception.toString());
        }
    }

    /**
     * 이 개정을 끝까지 돈다.
     *
     * @param route 개정
     * @return 도중에 도착한 새 개정. 없으면 {@code null} (여정 끝)
     */
    private @Nullable AssignedRoute drive(AssignedRoute route) throws InterruptedException {
        List<ScanCall> calls = simulator.remainingCalls(route, progress);
        for (ScanCall call : calls) {
            AssignedRoute revised = revisions.poll(pacer.waitNanosUntil(call.occurredAt()), TimeUnit.NANOSECONDS);
            if (revised != null) {
                return revised;
            }
            if (!send(route, call)) {
                abandoned = true;
                tally.routeAbandoned();
                return null;
            }
            progress = progress.after(call);
        }
        // 마지막 스캔 뒤에 도착한 개정을 놓치지 않는다. 그 개정의 stop 은 대개 전부 종결이라
        // 빈 계획이 나오고 여정이 곧 끝나지만, 새 주문이 실려 왔다면 그것은 가야 할 곳이다.
        return revisions.poll();
    }

    /**
     * 스캔 하나를 보낸다. 404 면 상한까지 다시 보낸다.
     *
     * @return 포기했으면 {@code false}
     */
    private boolean send(AssignedRoute route, ScanCall call) throws InterruptedException {
        long deadline = nanoTime.getAsLong() + retryLimitNanos;
        while (true) {
            ScanClient.Response response = scans.report(routeId, call);
            tally.scanSent();
            if (!response.isNotYetKnown()) {
                tally.record(response);
                return true;
            }
            if (nanoTime.getAsLong() >= deadline) {
                tally.record(response);
                // 주소도 주문 id 도 남기지 않는다 (§9.3).
                log.warn("""
                        tracking 이 이 라우트를 모른다. {}초를 기다렸고 포기한다. \
                        routeId={}, revision={}, seq={}, type={}. \
                        같은 route.assigned 를 두 컨슈머 그룹이 따로 읽으므로 잠깐의 404 는 정상이지만, \
                        이만큼 길면 tracking 이 그 이벤트를 받지 못한 것이다.""",
                        retryLimitNanos / 1_000_000_000L, routeId, route.revision(),
                        call.stopSeq(), call.type());
                return false;
            }
            tally.scanRetried();
            sleeper.sleepNanos(RETRY_INTERVAL_NANOS);
        }
    }
}
