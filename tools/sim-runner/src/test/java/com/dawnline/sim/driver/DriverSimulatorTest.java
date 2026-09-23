package com.dawnline.sim.driver;

import static com.dawnline.sim.driver.DriverFixtures.DEPARTURE;
import static com.dawnline.sim.driver.DriverFixtures.DRIVER;
import static com.dawnline.sim.driver.DriverFixtures.ROUTE;
import static com.dawnline.sim.driver.DriverFixtures.minutesAfterDeparture;
import static com.dawnline.sim.driver.DriverFixtures.order;
import static com.dawnline.sim.driver.DriverFixtures.route;
import static com.dawnline.sim.driver.DriverFixtures.stop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.sim.driver.DriverFixtures.FixedJitter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 기사가 라우트를 어떻게 도는가 — 순수 함수. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DriverSimulatorTest {

    private static final Jitter ON_TIME = new FixedJitter(0.0, 0L, false);

    private static List<ScanCall> calls(Jitter jitter) {
        return new DriverSimulator(jitter).remainingCalls(route(1), TripProgress.start());
    }

    @Test
    void 계획대로_돌면_스캔은_출발_하나에_stop_마다_둘이다() {
        List<ScanCall> calls = calls(ON_TIME);

        // TrackingPublishIT 이 고정한 팬아웃(2 × stop 수, DEPARTED_CAMP 는 발행 0)과 같은 셈이다.
        assertThat(calls).hasSize(1 + 2 * 3);
        assertThat(calls).extracting(ScanCall::type).containsExactly(
                ScanType.DEPARTED_CAMP,
                ScanType.ARRIVED, ScanType.COMPLETED,
                ScanType.ARRIVED, ScanType.COMPLETED,
                ScanType.ARRIVED, ScanType.COMPLETED);
    }

    @Test
    void 스캔은_그_stop_의_송장을_싣고_캠프_출발만_비운다() {
        // 서버가 대상을 찾는 열쇠다 (ADR-047 결정 1). 번호는 개정이 뜻을 바꾸지만 송장은 아니다.
        List<ScanCall> calls = calls(ON_TIME);

        assertThat(calls.getFirst().orderIds())
                .as("캠프 출발은 라우트의 사건이라 열쇠가 라우트다")
                .isEmpty();
        assertThat(calls.subList(1, calls.size()))
                .allSatisfy(call -> assertThat(call.orderIds())
                        .as("stop %s 의 송장", call.stopSeq())
                        .containsExactly(order(call.stopSeq())));
    }

    @Test
    void 캠프_출발이_아닌_스캔에_송장이_없으면_보내기_전에_터진다() {
        // 서버가 400 으로 답할 요청이다. 그 400 은 이 도구의 결함이지 시나리오의 결과가 아니다.
        assertThatThrownBy(() -> new ScanCall(1, List.of(), ScanType.ARRIVED, DEPARTURE, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderIds");
        assertThatThrownBy(() -> new ScanCall(1, List.of(order(1)), ScanType.DEPARTED_CAMP,
                DEPARTURE, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderIds");
    }

    @Test
    void 계획대로_돌면_시각이_계획_그대로다() {
        List<ScanCall> calls = calls(ON_TIME);

        assertThat(calls.getFirst().occurredAt()).isEqualTo(DEPARTURE);
        assertThat(minutesAfterDeparture(calls.get(1).occurredAt())).isEqualTo(20);
        assertThat(minutesAfterDeparture(calls.get(3).occurredAt())).isEqualTo(40);
        assertThat(minutesAfterDeparture(calls.get(5).occurredAt())).isEqualTo(60);
    }

    @Test
    void 주입한_지연이_곧_편차다() {
        // 구간은 20 · 15 · 15 분이고 절반을 더 쓴다 → 10 · 7.5 · 7.5 분이 쌓인다.
        // 이 값이 tracking 이 "실제 도착 − 계획 도착" 으로 계산하는 편차와 같다 (§5.4).
        List<ScanCall> calls = calls(new FixedJitter(0.5, 0L, false));

        assertThat(deviationMinutes(calls.get(1), 20)).isEqualTo(10);
        assertThat(deviationMinutes(calls.get(3), 40)).isEqualTo(17);
        assertThat(deviationMinutes(calls.get(5), 60)).isEqualTo(25);
    }

    @Test
    void 출발_지연은_뒤따르는_stop_전부로_전파된다() {
        // §5.4 가 말하는 가장 흔한 지연 원인. 첫 도착 스캔 전에 이미 알 수 있어야 한다.
        List<ScanCall> calls = calls(new FixedJitter(0.0, Duration.ofMinutes(30).toSeconds(), false));

        assertThat(minutesAfterDeparture(calls.getFirst().occurredAt())).isEqualTo(30);
        assertThat(deviationMinutes(calls.get(1), 20)).isEqualTo(30);
        assertThat(deviationMinutes(calls.get(5), 60)).isEqualTo(30);
    }

    @Test
    void 취소된_stop_은_가지_않은_곳이라_앵커를_옮기지_않는다() {
        AssignedRoute route = new AssignedRoute(ROUTE, 1, DRIVER, new AssignedRoute.Summary(DEPARTURE),
                List.of(stop(1, 20, 5, null), stop(2, 40, 5, "CANCELLED"), stop(3, 60, 5, null)));

        List<ScanCall> calls = new DriverSimulator(ON_TIME).remainingCalls(route, TripProgress.start());

        assertThat(calls).extracting(ScanCall::stopSeq).containsExactly(1, 1, 1, 3, 3);
        // 앵커가 stop 2 로 옮겨가지 않으므로 1 → 3 은 한 구간(35분)이 된다 — 가지 않은 지점에서
        // 체류할 수 없기 때문이다. 계획대로 돌면 도착은 계획(60분)과 같고, 지연이 있으면
        // 건너뛴 체류 시간만큼 덜 밀린다.
        assertThat(minutesAfterDeparture(calls.get(3).occurredAt())).isEqualTo(60);
    }

    @Test
    void 전달에_실패해도_다음_stop_으로_간다() {
        List<ScanCall> calls = calls(new FixedJitter(0.0, 0L, true));

        assertThat(calls).extracting(ScanCall::type).containsExactly(
                ScanType.DEPARTED_CAMP,
                ScanType.ARRIVED, ScanType.FAILED,
                ScanType.ARRIVED, ScanType.FAILED,
                ScanType.ARRIVED, ScanType.FAILED);
        assertThat(calls.get(2).failureReason()).isNotBlank();
        // 사유는 FAILED 에만 붙는다. 다른 종류에 붙이면 tracking 이 400 으로 답한다.
        assertThat(calls).filteredOn(call -> call.type() != ScanType.FAILED)
                .allSatisfy(call -> assertThat(call.failureReason()).isNull());
    }

    @Test
    void 시각은_언제나_단조_증가한다() {
        List<ScanCall> calls = calls(new FixedJitter(0.3, 600L, false));

        assertThat(calls).extracting(ScanCall::occurredAt).isSorted();
    }

    @Test
    void 개정이_오면_남은_스캔이_새_순서를_따른다() {
        DriverSimulator simulator = new DriverSimulator(ON_TIME);
        List<ScanCall> first = simulator.remainingCalls(route(1), TripProgress.start());

        // stop 1 까지 끝낸 자리에서 개정 2 를 받는다. 새 개정은 3 을 2 보다 먼저 돈다.
        TripProgress progress = TripProgress.start();
        for (ScanCall call : first.subList(0, 3)) {
            progress = progress.after(call);
        }
        AssignedRoute revised = new AssignedRoute(ROUTE, 2, DRIVER, new AssignedRoute.Summary(DEPARTURE),
                List.of(stop(1, 20, 5, null), stop(3, 45, 5, null), stop(2, 65, 5, null)));

        List<ScanCall> remaining = simulator.remainingCalls(revised, progress);

        assertThat(remaining).extracting(ScanCall::stopSeq).containsExactly(3, 3, 2, 2);
        // 캠프 출발은 다시 보내지 않는다 — 이미 떠났다.
        assertThat(remaining).extracting(ScanCall::type).doesNotContain(ScanType.DEPARTED_CAMP);
    }

    @Test
    void 끝낸_stop_은_갔던_곳이라_앵커를_옮기고_다시_스캔하지_않는다() {
        // tracking 의 "개정은 종결을 되돌리지 않는다" 와 대칭이다. 축도 같다 — seq 가 아니라 주문이다.
        DriverSimulator simulator = new DriverSimulator(ON_TIME);
        TripProgress progress = new TripProgress(DEPARTURE.plus(Duration.ofMinutes(25)),
                Set.of(order(1), order(2)));

        List<ScanCall> remaining = simulator.remainingCalls(route(2), progress);

        assertThat(remaining).extracting(ScanCall::stopSeq).containsExactly(3, 3);
        // 앵커가 stop 2 의 계획 완료(45분)로 옮겨졌으므로 남은 구간은 15분이다: 25 + 15 = 40.
        // 옮기지 않았다면 캠프부터 다시 재어 60분 구간이 되고 도착은 85분이 된다 — 갔던 곳에서
        // 쓴 시간을 두 번 세는 것이다.
        assertThat(minutesAfterDeparture(remaining.getFirst().occurredAt())).isEqualTo(40);
    }

    @Test
    void 주문이_하나라도_남은_stop_은_다시_간다() {
        // 개정이 배달된 주문과 새 주문을 같은 지점에 합쳐 놓은 경우다. 가지 않으면 새 주문이
        // 조용히 배달되지 않는다. 한 번 더 스캔하는 쪽은 tracking 이 STALE 로 흡수한다.
        AssignedRoute merged = new AssignedRoute(ROUTE, 2, DRIVER, new AssignedRoute.Summary(DEPARTURE),
                List.of(new AssignedRoute.PlannedStop(1, List.of(order(1), order(9)), 37.5, 127.0,
                        DEPARTURE.plus(Duration.ofMinutes(20)), 300, null)));

        List<ScanCall> remaining = new DriverSimulator(ON_TIME)
                .remainingCalls(merged, new TripProgress(DEPARTURE, Set.of(order(1))));

        assertThat(remaining).extracting(ScanCall::stopSeq).containsExactly(1, 1);
    }

    @Test
    void 같은_seed_는_같은_스캔_열을_낸다() {
        assertThat(withSeed(20260919L)).isEqualTo(withSeed(20260919L));
        assertThat(withSeed(20260919L)).isNotEqualTo(withSeed(20260920L));
    }

    @Test
    void 계획_시각이_없으면_지어내지_않고_멈춘다() {
        AssignedRoute noDeparture = new AssignedRoute(ROUTE, 1, DRIVER, new AssignedRoute.Summary(null),
                List.of(stop(1, 20, 5, null)));

        assertThatThrownBy(() -> new DriverSimulator(ON_TIME)
                .remainingCalls(noDeparture, TripProgress.start()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plannedDeparture");
    }

    private static List<ScanCall> withSeed(long seed) {
        Jitter jitter = new SeededJitter(seed,
                value -> RandomGeneratorFactory.of("L64X128MixRandom").create(value),
                0.8, 0.5, 0.2, 1800L);
        return new DriverSimulator(jitter).remainingCalls(route(1), TripProgress.start());
    }

    private static long deviationMinutes(ScanCall call, int plannedMinutes) {
        Instant planned = DEPARTURE.plus(Duration.ofMinutes(plannedMinutes));
        return Duration.between(planned, call.occurredAt()).toMinutes();
    }
}
