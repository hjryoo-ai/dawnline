package com.dawnline.sim.driver;

import static com.dawnline.sim.driver.DriverFixtures.DEPARTURE;
import static com.dawnline.sim.driver.DriverFixtures.DRIVER;
import static com.dawnline.sim.driver.DriverFixtures.ROUTE;
import static com.dawnline.sim.driver.DriverFixtures.route;
import static com.dawnline.sim.driver.DriverFixtures.stop;
import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.sim.driver.DriverFixtures.FixedJitter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 여정 하나. 스레드를 띄우지 않고 {@link DriverTrip#run()} 을 직접 부른다 — 개정은 스캔
 * 클라이언트가 그 자리에서 넣으므로 순서가 정해져 있다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DriverTripTest {

    private static final ScanClient.Response OK =
            ScanClient.Response.of(200, null, Map.of("APPLIED", 1));
    private static final ScanClient.Response NOT_FOUND =
            ScanClient.Response.of(404, "shipment-not-found", Map.of());

    private final DriverTally tally = new DriverTally();
    private final AtomicLong nanos = new AtomicLong();
    private final List<ScanCall> sent = new ArrayList<>();

    /** 재시도 대기는 단조 시계를 그만큼 앞으로 민다 — 실제로 자지 않는다. */
    private final com.dawnline.sim.order.Sleeper sleeper = nanos::addAndGet;

    private DriverTrip trip(ScanClient scans, long retryLimitNanos) {
        return new DriverTrip(ROUTE, new DriverSimulator(new FixedJitter(0.0, 0L, false)), scans,
                new TripPacer(0.0, nanos::get), sleeper, nanos::get, retryLimitNanos, tally);
    }

    /** 스캔을 기록하고, 필요하면 그 자리에서 개정을 넣는다. */
    private ScanClient recording(BiConsumer<Integer, DriverTrip> onCall, DriverTrip[] holder) {
        return (routeId, call) -> {
            sent.add(call);
            onCall.accept(sent.size(), holder[0]);
            return OK;
        };
    }

    private DriverReport report() {
        return tally.snapshot("test", 1);
    }

    @Test
    void 라우트를_끝까지_돌면_스캔이_전부_나간다() throws InterruptedException {
        DriverTrip[] holder = new DriverTrip[1];
        DriverTrip trip = trip(recording((n, t) -> { }, holder), 0L);
        holder[0] = trip;
        trip.revise(route(1));

        trip.run();

        assertThat(sent).hasSize(7);
        assertThat(report().completedRoutes()).isEqualTo(1);
        assertThat(report().abandonedRoutes()).isZero();
        assertThat(report().outcomes()).containsEntry("APPLIED", 7L);
    }

    @Test
    void 아직_모르는_라우트는_잠시_뒤_다시_보낸다() {
        // 원인은 경합이다 — tracking 과 이 도구가 같은 route.assigned 를 다른 컨슈머 그룹으로 읽는다.
        int[] calls = {0};
        DriverTrip trip = trip((routeId, call) -> calls[0]++ < 2 ? NOT_FOUND : OK,
                Duration.ofSeconds(30).toNanos());
        trip.revise(route(1));

        trip.run();

        assertThat(report().completedRoutes()).isEqualTo(1);
        assertThat(report().retries()).isEqualTo(2);
        assertThat(report().abandonedRoutes()).isZero();
    }

    @Test
    void 상한을_넘기면_그_라우트를_포기한다() {
        // 조용히 무한 재시도하는 도구는 시나리오 결과를 오염시킨다 — tracking 이 이벤트를 아예
        // 못 받은 경우와 늦게 받은 경우가 구별되지 않고, 실행은 끝나지 않는다.
        DriverTrip trip = trip((routeId, call) -> NOT_FOUND, Duration.ofSeconds(3).toNanos());
        trip.revise(route(1));

        trip.run();

        assertThat(report().abandonedRoutes()).isEqualTo(1);
        assertThat(report().completedRoutes()).isZero();
        assertThat(report().retries()).isEqualTo(3);
        assertThat(report().failures()).containsEntry("shipment-not-found", 1L);
        // 첫 스캔에서 막혔으므로 뒤의 stop 은 시도조차 하지 않는다.
        assertThat(report().scans()).isEqualTo(4);
    }

    @Test
    void 개정이_오면_대기를_깨고_새_순서를_따른다() throws InterruptedException {
        AssignedRoute revised = new AssignedRoute(ROUTE, 2, DRIVER, new AssignedRoute.Summary(DEPARTURE),
                List.of(stop(1, 20, 5, null), stop(3, 45, 5, null), stop(2, 65, 5, null)));
        DriverTrip[] holder = new DriverTrip[1];
        // stop 1 을 끝낸 직후(세 번째 스캔) 재계획이 도착한다.
        DriverTrip trip = trip(recording((n, t) -> {
            if (n == 3) {
                t.revise(revised);
            }
        }, holder), 0L);
        holder[0] = trip;
        trip.revise(route(1));

        trip.run();

        assertThat(sent).extracting(ScanCall::stopSeq).containsExactly(1, 1, 1, 3, 3, 2, 2);
        assertThat(report().completedRoutes()).isEqualTo(1);
    }

    @Test
    void 마지막_스캔_뒤에_온_개정도_놓치지_않는다() {
        // 새 주문이 실려 왔다면 그것은 가야 할 곳이다. 종결된 stop 만 있으면 빈 계획이 나와
        // 여정이 곧 끝난다.
        AssignedRoute added = new AssignedRoute(ROUTE, 2, DRIVER, new AssignedRoute.Summary(DEPARTURE),
                List.of(stop(4, 80, 5, null)));
        DriverTrip[] holder = new DriverTrip[1];
        DriverTrip trip = trip(recording((n, t) -> {
            if (n == 7) {
                t.revise(added);
            }
        }, holder), 0L);
        holder[0] = trip;
        trip.revise(route(1));

        trip.run();

        // 맨 앞의 1 은 DEPARTED_CAMP 다 — 경로 변수가 필요해 첫 stop 의 순번을 쓴다.
        assertThat(sent).extracting(ScanCall::stopSeq).containsExactly(1, 1, 1, 2, 2, 3, 3, 4, 4);
    }
}
