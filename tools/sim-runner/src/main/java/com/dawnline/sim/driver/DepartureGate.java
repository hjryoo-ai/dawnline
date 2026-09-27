package com.dawnline.sim.driver;

import com.dawnline.sim.order.Sleeper;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 창 시나리오의 기사는 tracking 이 그 라우트들을 <strong>반영한 뒤에</strong> 출발한다 (ADR-067 후속, DESIGN.md 부록 A 6단계).
 *
 * <h2>왜 떼나</h2>
 * 운영에서 계획의 반영(00:00 직후)과 스캔(출발 뒤 7시간)은 겹치지 않는다. 배속 600 의 기사는 7시간치 스캔을 반영 버스트와 같은
 * 2분에 몰았고, 첫 {@code peak-day} 에서 tracking 의 커넥션 풀과 CPU 를 채워 반영 한 건이 약 1초가 됐다 — 반영 경로만 떼어 재면
 * 100-stop 라우트 하나에 29.5 ms 였다(2026-09-27, 근거: 관측). 두 부하를 시간으로 떼는 것은 도구의 편의가 아니라 <em>모델의
 * 교정</em>이고, 그래야 리포트의 두 줄(반영 처리량 · 스캔 부하)이 서로를 오염시키지 않는다.
 *
 * <h2>두 조건을 순서대로</h2>
 * <ol>
 *   <li><strong>이 도구가 창의 라우트를 전부 받았다</strong> — 받았다는 것은 그 레코드가 로그에 있다는 뜻이다.</li>
 *   <li><strong>그 뒤에 잰 tracking 그룹의 랙이 0</strong> — 커밋이 그때의 끝 오프셋에 닿았고, 1 의 레코드는 전부 그 끝 앞에 있다.</li>
 * </ol>
 * 둘째만 보면 라우트가 아직 발행되지 않은 순간(outbox 가 밀린 순간)의 0 을 반영 완료로 읽는다.
 *
 * <h2>상한을 넘기면 그래도 연다</h2>
 * 기사가 돌지 않으면 라우트가 끝나지 않고 증차한 차량이 비활성화되지 않는다(ADR-067 결정 5). 대신 결과가 실패를 말하고 종료
 * 코드가 된다 — 그 실행의 스캔 부하는 다시 반영과 겹쳤고, 리포트의 두 줄은 떼어지지 않았다.
 */
public final class DepartureGate {

    private static final Logger log = LoggerFactory.getLogger(DepartureGate.class);

    /** 조건을 다시 보는 간격. 랙 질의 하나가 브로커 왕복 넷이라 더 잦을 이유가 없다. */
    static final long POLL_INTERVAL_NANOS = 500_000_000L;

    private final DriverFleet fleet;
    private final ApplyLag lag;
    private final Sleeper sleeper;
    private final LongSupplier nanoTime;
    private final Duration timeout;

    /**
     * @param fleet    기사들 — 출발을 붙잡고 놓는다
     * @param lag      tracking 그룹의 랙
     * @param sleeper  대기
     * @param nanoTime 단조 시계
     * @param timeout  두 조건을 합쳐 기다리는 상한
     */
    public DepartureGate(DriverFleet fleet, ApplyLag lag, Sleeper sleeper, LongSupplier nanoTime, Duration timeout) {
        this.fleet = Objects.requireNonNull(fleet, "fleet");
        this.lag = Objects.requireNonNull(lag, "lag");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /** 출발을 붙잡는다 — 수신을 켜기 <strong>전에</strong> 부른다. 뒤면 그 사이에 받은 라우트의 기사가 이미 떠났다. */
    public void hold() {
        fleet.holdDepartures();
    }

    /**
     * 두 조건을 기다리고 출발을 놓는다. 상한을 넘겨도 놓는다.
     *
     * @param waves  창의 웨이브
     * @param routes 그 웨이브들의 라우트 수 — 계획이 낸 수
     * @return 기다린 결과
     * @throws InterruptedException 대기 중 인터럽트. 그때도 출발을 놓는다
     */
    public Result openAfterApplied(Set<UUID> waves, int routes) throws InterruptedException {
        long started = nanoTime.getAsLong();
        long deadline = started + timeout.toNanos();
        try {
            int received;
            while ((received = fleet.receivedIn(waves)) < routes) {
                if (nanoTime.getAsLong() >= deadline) {
                    return timedOut(started, routes, received, -1L, "도구가 창의 라우트를 다 받지 못했다");
                }
                sleeper.sleepNanos(POLL_INTERVAL_NANOS);
            }
            long allReceived = nanoTime.getAsLong();

            long remaining = -1L;
            while (true) {
                try {
                    remaining = lag.remaining();
                } catch (IllegalStateException e) {
                    log.warn("tracking 그룹의 랙을 묻지 못했다 — 다시 묻는다: {}", e.getMessage());
                    remaining = -1L;
                }
                if (remaining == 0L) {
                    break;
                }
                if (nanoTime.getAsLong() >= deadline) {
                    return timedOut(started, routes, received, remaining, "tracking 의 route.assigned 랙이 0 이 되지 않았다");
                }
                sleeper.sleepNanos(POLL_INTERVAL_NANOS);
            }
            Result result = new Result(true, routes, received, Duration.ofNanos(allReceived - started),
                    Duration.ofNanos(nanoTime.getAsLong() - allReceived), 0L);
            log.info("기사 출발 — 라우트 {}대를 받고 {}초, tracking 랙 0 까지 {}초", routes,
                    result.untilReceived().toMillis() / 1000.0, result.untilApplied().toMillis() / 1000.0);
            return result;
        } finally {
            fleet.releaseDepartures();
        }
    }

    private Result timedOut(long started, int routes, int received, long remaining, String what) {
        log.error("{}초 안에 {} — 기사를 그래도 출발시킨다. 이 실행의 스캔 부하는 반영과 겹친다(라우트 {}/{}, 랙 {})",
                timeout.toSeconds(), what, received, routes, remaining < 0 ? "모름" : remaining);
        return new Result(false, routes, received, Duration.ofNanos(nanoTime.getAsLong() - started), Duration.ZERO,
                remaining);
    }

    /**
     * 출발 전 기다림.
     *
     * @param inTime        두 조건이 상한 안에 섰는가. 아니면 실행의 실패다
     * @param routes        기다린 라우트 수
     * @param received      도구가 받은 라우트 수
     * @param untilReceived 라우트를 다 받기까지
     * @param untilApplied  그 뒤 tracking 랙 0 까지 — 계획이 끝난 뒤의 <em>반영 꼬리</em>다
     * @param remaining     출발 때의 랙. 모르면 −1
     */
    public record Result(boolean inTime, int routes, int received, Duration untilReceived, Duration untilApplied,
            long remaining) {

        /** 사람이 읽는 한 줄. */
        public String toMarkdown() {
            return inTime
                    ? "| 출발 전 반영 대기 | 라우트 %d대 수신까지 %.1f초 + tracking 랙 0 까지 %.1f초 |\n".formatted(routes,
                            untilReceived.toMillis() / 1000.0, untilApplied.toMillis() / 1000.0)
                    : "| 출발 전 반영 대기 | ✗ 상한 — 라우트 %d/%d, 랙 %s, %.1f초 뒤 그래도 출발 |\n".formatted(received, routes,
                            remaining < 0 ? "모름" : String.valueOf(remaining), untilReceived.toMillis() / 1000.0);
        }
    }
}
