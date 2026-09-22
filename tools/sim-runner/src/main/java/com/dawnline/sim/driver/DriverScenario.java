package com.dawnline.sim.driver;

import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 기사 시뮬레이션 한 판 (IMPLEMENTATION_PLAN Phase 5-2).
 *
 * <p>순서가 전부다. 수신을 <strong>먼저</strong> 켜고, 주문이 흘러 라우트가 확정되기를 기다리고,
 * 기사들이 다 돌면 결과를 낸다. 수신을 나중에 켜면 그 사이에 확정된 라우트를 놓치고, 놓친 것은
 * 「라우트가 안 왔다」로 보여서 원인이 도구인지 스택인지 구별되지 않는다.
 */
public final class DriverScenario implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DriverScenario.class);

    private final RouteFeed feed;
    private final DriverFleet fleet;
    private final DriverTally tally;
    private final int expectedRoutes;
    private final Duration timeout;

    /**
     * @param feed           {@code route.assigned} 수신
     * @param fleet          기사들
     * @param tally          집계
     * @param expectedRoutes 기다릴 라우트 수
     * @param timeout        라우트를 기다리는 상한
     */
    public DriverScenario(RouteFeed feed, DriverFleet fleet, DriverTally tally, int expectedRoutes,
            Duration timeout) {
        this.feed = Objects.requireNonNull(feed, "feed");
        this.fleet = Objects.requireNonNull(fleet, "fleet");
        this.tally = Objects.requireNonNull(tally, "tally");
        this.expectedRoutes = expectedRoutes;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /** 수신을 켠다. */
    public void open() {
        log.info("기사 시뮬레이터 대기: 라우트 {}대, 상한 {}초", expectedRoutes, timeout.toSeconds());
        feed.open();
    }

    /**
     * 기사들이 다 돌 때까지 기다리고 결과를 낸다.
     *
     * @param name 시나리오 이름 (보고용)
     * @return 결과
     * @throws InterruptedException 대기 중 인터럽트
     */
    public DriverReport awaitAndReport(String name) throws InterruptedException {
        boolean all = fleet.awaitRoutes(timeout);
        if (!all) {
            log.error("""
                    {}초 안에 라우트 {}대를 끝내지 못했다. \
                    웨이브가 아직 안 닫혔거나(§3.1 컷오프), 계획이 돌지 않았거나, \
                    route.assigned 가 발행되지 않은 것이다 — 아래 표의 "라우트" 행이 0 이면 셋 중 앞의 둘이다.""",
                    timeout.toSeconds(), expectedRoutes);
        }
        return tally.snapshot(name, expectedRoutes);
    }

    @Override
    public void close() {
        feed.close();
        fleet.close();
    }
}
