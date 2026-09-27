package com.dawnline.sim.driver;

import static com.dawnline.sim.driver.DriverFixtures.DEPARTURE;
import static com.dawnline.sim.driver.DriverFixtures.DRIVER;
import static com.dawnline.sim.driver.DriverFixtures.stop;
import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.sim.driver.DriverFixtures.FixedJitter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 창 시나리오의 출발 — 라우트를 다 받고, <strong>그 뒤에</strong> tracking 의 랙이 0 이면 (ADR-067 후속).
 *
 * <p>시계는 대기마다 한 간격씩 가는 가짜다 — 상한을 실제로 기다리지 않는다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DepartureGateTest {

    private static final UUID WAVE = UUID.fromString("0199a000-0000-7000-8000-0000000000a1");

    private final DriverTally tally = new DriverTally();
    private final ConcurrentLinkedQueue<UUID> scanned = new ConcurrentLinkedQueue<>();
    private final AtomicLong now = new AtomicLong();

    /** 대기마다 그만큼 시계가 간다. 대기 사이에 할 일이 있으면 {@code onSleep} 이 한다. */
    private final List<Runnable> onSleep = new ArrayList<>();

    private DriverFleet fleet() {
        ScanClient scans = (routeId, call) -> {
            scanned.add(routeId);
            return ScanClient.Response.of(200, null, Map.of("APPLIED", 1));
        };
        return new DriverFleet(0, new DriverSimulator(new FixedJitter(0.0, 0L, false)), scans, () -> 0.0,
                nanos -> { }, () -> 0L, 0L, tally);
    }

    private DepartureGate gate(DriverFleet fleet, ApplyLag lag, Duration timeout) {
        return new DepartureGate(fleet, lag, nanos -> {
            now.addAndGet(nanos);
            if (!onSleep.isEmpty()) {
                onSleep.removeFirst().run();
            }
        }, now::get, timeout);
    }

    private static AssignedRoute route(UUID routeId) {
        return new AssignedRoute(routeId, 1, DRIVER, new AssignedRoute.Summary(DEPARTURE),
                List.of(stop(1, 20, 5, null)), WAVE);
    }

    @Test
    void 라우트를_다_받고_랙이_0_이면_출발한다() throws InterruptedException {
        try (DriverFleet fleet = fleet()) {
            DepartureGate gate = gate(fleet, () -> 0L, Duration.ofSeconds(60));
            gate.hold();
            fleet.assign(route(DriverFixtures.ROUTE));

            DepartureGate.Result result = gate.openAfterApplied(Set.of(WAVE), 1);

            assertThat(result.inTime()).isTrue();
            assertThat(fleet.awaitRoutes(Set.of(WAVE), 1, Duration.ofSeconds(10))).isTrue();
            assertThat(scanned).contains(DriverFixtures.ROUTE);
        }
    }

    @Test
    void 라우트를_다_받기_전의_랙_0_은_반영_완료가_아니다() throws InterruptedException {
        // outbox 가 밀린 순간에는 tracking 도 읽을 것이 없어 랙이 0 이다. 그 0 을 믿으면 아직 발행되지 않은 라우트의 기사가
        // 떠나고 404 를 받는다 — 떼려던 두 부하가 다시 겹친다.
        List<String> order = new ArrayList<>();
        try (DriverFleet fleet = fleet()) {
            DepartureGate gate = gate(fleet, () -> {
                order.add("lag");
                return 0L;
            }, Duration.ofSeconds(60));
            gate.hold();
            onSleep.add(() -> {
                order.add("route");
                fleet.assign(route(DriverFixtures.ROUTE));
            });

            DepartureGate.Result result = gate.openAfterApplied(Set.of(WAVE), 1);

            assertThat(result.inTime()).isTrue();
            assertThat(order).as("랙은 라우트를 다 받은 뒤에 묻는다").containsExactly("route", "lag");
        }
    }

    @Test
    void 랙이_남아_있으면_기다렸다가_0_이_되면_출발한다() throws InterruptedException {
        AtomicLong lag = new AtomicLong(3);
        try (DriverFleet fleet = fleet()) {
            DepartureGate gate = gate(fleet, () -> lag.getAndUpdate(n -> Math.max(0, n - 1)), Duration.ofSeconds(60));
            gate.hold();
            fleet.assign(route(DriverFixtures.ROUTE));

            DepartureGate.Result result = gate.openAfterApplied(Set.of(WAVE), 1);

            assertThat(result.inTime()).isTrue();
            assertThat(result.untilApplied()).isEqualTo(Duration.ofNanos(3 * DepartureGate.POLL_INTERVAL_NANOS));
        }
    }

    @Test
    void 상한을_넘기면_실패를_말하고_그래도_출발시킨다() throws InterruptedException {
        // 기사가 안 돌면 라우트가 끝나지 않고 증차한 차량이 비활성화되지 않는다(ADR-067 결정 5) — 그래서 놓는다.
        try (DriverFleet fleet = fleet()) {
            DepartureGate gate = gate(fleet, () -> 7L, Duration.ofSeconds(5));
            gate.hold();
            fleet.assign(route(DriverFixtures.ROUTE));

            DepartureGate.Result result = gate.openAfterApplied(Set.of(WAVE), 1);

            assertThat(result.inTime()).isFalse();
            assertThat(result.remaining()).isEqualTo(7L);
            assertThat(result.toMarkdown()).contains("✗");
            assertThat(fleet.awaitRoutes(Set.of(WAVE), 1, Duration.ofSeconds(10))).as("놓았다").isTrue();
        }
    }

    @Test
    void 랙을_묻지_못하면_0_으로_접지_않고_다시_묻는다() throws InterruptedException {
        AtomicLong calls = new AtomicLong();
        try (DriverFleet fleet = fleet()) {
            DepartureGate gate = gate(fleet, () -> {
                if (calls.incrementAndGet() < 3) {
                    throw new IllegalStateException("브로커가 없다");
                }
                return 0L;
            }, Duration.ofSeconds(60));
            gate.hold();
            fleet.assign(route(DriverFixtures.ROUTE));

            DepartureGate.Result result = gate.openAfterApplied(Set.of(WAVE), 1);

            assertThat(result.inTime()).isTrue();
            assertThat(calls).hasValue(3);
        }
    }

    @Test
    void 라우트가_다_오지_않으면_랙을_묻지_않고_상한에서_실패한다() throws InterruptedException {
        try (DriverFleet fleet = fleet()) {
            DepartureGate gate = gate(fleet, () -> {
                throw new AssertionError("라우트를 다 받기 전에 랙을 물었다");
            }, Duration.ofSeconds(5));
            gate.hold();
            fleet.assign(route(DriverFixtures.ROUTE));

            DepartureGate.Result result = gate.openAfterApplied(Set.of(WAVE), 2);

            assertThat(result.inTime()).isFalse();
            assertThat(result.received()).isEqualTo(1);
            assertThat(result.remaining()).as("묻지 않았으니 모른다").isEqualTo(-1L);
        }
    }
}
