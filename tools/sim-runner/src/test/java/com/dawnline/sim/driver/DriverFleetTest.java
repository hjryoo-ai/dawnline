package com.dawnline.sim.driver;

import static com.dawnline.sim.driver.DriverFixtures.DEPARTURE;
import static com.dawnline.sim.driver.DriverFixtures.DRIVER;
import static com.dawnline.sim.driver.DriverFixtures.route;
import static com.dawnline.sim.driver.DriverFixtures.stop;
import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.sim.driver.DriverFixtures.FixedJitter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 기사들 — 특히 <strong>중복 소비를 거르는 자리</strong>.
 *
 * <p>이 도구에는 {@code processed_events} 가 없다(불변규칙 2 의 예외). 예외가 성립하는 이유는
 * 「도구라서」가 아니라 하류가 멱등이라서이고, 여기서 확인하는 것은 그 거름망이 tracking 의
 * {@code route_revisions} 와 <em>같은 모양</em>인지다 — 재전달(같은 개정)과 순서 역전(낮은 개정)을
 * 함께 흡수한다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DriverFleetTest {

    private static final UUID OTHER_ROUTE = UUID.fromString("0199a000-0000-7000-8000-0000000000f2");

    private final DriverTally tally = new DriverTally();
    private final ConcurrentLinkedQueue<UUID> scanned = new ConcurrentLinkedQueue<>();

    private DriverFleet fleet(int expectedRoutes) {
        ScanClient scans = (routeId, call) -> {
            scanned.add(routeId);
            return ScanClient.Response.of(200, null, Map.of("APPLIED", 1));
        };
        return new DriverFleet(expectedRoutes, new DriverSimulator(new FixedJitter(0.0, 0L, false)),
                scans, () -> 0.0, nanos -> { }, () -> 0L, 0L, tally);
    }

    private DriverReport report(int expectedRoutes) {
        return tally.snapshot("test", expectedRoutes);
    }

    @Test
    void 이미_본_개정은_버린다() throws InterruptedException {
        try (DriverFleet fleet = fleet(1)) {
            fleet.assign(route(1));
            fleet.assign(route(1));   // 재전달
            fleet.assign(route(1));   // 또 재전달

            assertThat(report(1).revisions()).isEqualTo(1);
            assertThat(report(1).staleRevisions()).isEqualTo(2);
            assertThat(fleet.awaitRoutes(Duration.ofSeconds(10))).isTrue();
        }
    }

    @Test
    void 순서가_뒤바뀐_낮은_개정도_버린다() throws InterruptedException {
        try (DriverFleet fleet = fleet(1)) {
            fleet.assign(revision(3));
            fleet.assign(revision(2));

            assertThat(report(1).revisions()).isEqualTo(1);
            assertThat(report(1).staleRevisions()).isEqualTo(1);
            assertThat(fleet.awaitRoutes(Duration.ofSeconds(10))).isTrue();
        }
    }

    @Test
    void 더_높은_개정은_같은_기사에게_간다() throws InterruptedException {
        try (DriverFleet fleet = fleet(1)) {
            fleet.assign(revision(1));
            fleet.assign(revision(2));

            assertThat(report(1).revisions()).isEqualTo(2);
            assertThat(report(1).routes()).isEqualTo(1);
            assertThat(fleet.awaitRoutes(Duration.ofSeconds(10))).isTrue();
        }
    }

    @Test
    void 라우트마다_기사가_하나씩_붙는다() throws InterruptedException {
        try (DriverFleet fleet = fleet(2)) {
            fleet.assign(route(1));
            fleet.assign(new AssignedRoute(OTHER_ROUTE, 1, DRIVER, new AssignedRoute.Summary(DEPARTURE),
                    List.of(stop(1, 20, 5, null))));

            assertThat(fleet.awaitRoutes(Duration.ofSeconds(10))).isTrue();
            assertThat(report(2).routes()).isEqualTo(2);
            assertThat(report(2).completedRoutes()).isEqualTo(2);
            assertThat(scanned).contains(DriverFixtures.ROUTE, OTHER_ROUTE);
        }
    }

    @Test
    void 라우트가_오지_않으면_기다리다_시간이_다_된다() throws InterruptedException {
        try (DriverFleet fleet = fleet(1)) {
            assertThat(fleet.awaitRoutes(Duration.ofMillis(200))).isFalse();
            assertThat(report(1).routes()).isZero();
        }
    }

    private static AssignedRoute revision(int revision) {
        return new AssignedRoute(DriverFixtures.ROUTE, revision, DRIVER,
                new AssignedRoute.Summary(DEPARTURE), List.of(stop(1, 20, 5, null)));
    }
}
