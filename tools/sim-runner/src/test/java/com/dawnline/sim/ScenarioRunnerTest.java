package com.dawnline.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.sim.config.SimProperties;
import com.dawnline.sim.driver.DepartureGate;
import com.dawnline.sim.driver.DriverFleet;
import com.dawnline.sim.driver.DriverReport;
import com.dawnline.sim.driver.DriverScenario;
import com.dawnline.sim.driver.DriverSimulator;
import com.dawnline.sim.driver.DriverTally;
import com.dawnline.sim.driver.Jitter;
import com.dawnline.sim.driver.RouteFeed;
import com.dawnline.sim.driver.ScanClient;
import com.dawnline.sim.fleet.FakeOpsClient;
import com.dawnline.sim.fleet.FleetReport;
import com.dawnline.sim.fleet.PeakFleet;
import com.dawnline.sim.order.OrderClient;
import com.dawnline.sim.order.ScenarioReport;
import com.dawnline.sim.order.SmokeScenario;
import com.dawnline.sim.order.WindowStart;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 시나리오 실행과 종료 코드. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ScenarioRunnerTest {

    private static final SimProperties.Scenario SMOKE = new SimProperties.Scenario(
            5, 1000, 20260904L, 10, 0.25, Map.of("DAWN", 1), null, null, null, null);

    /** 기사까지 도는 시나리오. 기다릴 라우트는 0 이라 대기 없이 끝난다. */
    private static final SimProperties.Scenario WITH_DRIVER = new SimProperties.Scenario(
            3, 1000, 20260904L, 10, 0.25, Map.of("DAWN", 1),
            new SimProperties.Scenario.Driver(1, 0.0, 1, 0, "http://localhost:8084",
                    0.0, 0.0, 0.0, 0), null, null, null);

    private static final LongSupplier FROZEN_CLOCK = () -> 1_000_000_000L;

    /** 유효 시각 22:40 KST — `make sim-up` 의 기본값. 창 시나리오의 시작(22:58)이 18분 뒤다. */
    private static final Clock AT_2240_KST = Clock.fixed(Instant.parse("2026-09-27T13:40:00Z"), ZoneOffset.UTC);

    private static SimProperties.Scenario windowAt(String startAt) {
        return new SimProperties.Scenario(5, 1000, 20260927L, 10, 0.25, Map.of("DAWN", 1), null, startAt, null, null);
    }

    private static SimProperties properties(String selected) {
        return properties(selected, "");
    }

    /** 함대 단계가 있는 창 시나리오 둘 — 증차(feasible)와 증차 없음(as-is). 기사는 없다(라우트 수는 계획이 낸다). */
    private static SimProperties properties(String selected, String token) {
        return new SimProperties(selected, "http://localhost:8081", 5000,
                Map.of("smoke", SMOKE, "with-driver", WITH_DRIVER,
                        "window-ahead", windowAt("22:58"), "window-missed", windowAt("22:30"),
                        "peak", fleetAt(SimProperties.Scenario.Fleet.FEASIBLE),
                        "overload", fleetAt(SimProperties.Scenario.Fleet.AS_IS),
                        "turbulent", new SimProperties.Scenario(6, 1000, 20260928L, 10, 0.25, Map.of("DAWN", 1), null, "22:58",
                                SimProperties.Scenario.Fleet.FEASIBLE, new SimProperties.Scenario.Cancel(1.0, 0.5, 1000))),
                new SimProperties.Ops("http://localhost:8085", token, 60_000, 60, 20, 5));
    }

    private static SimProperties.Scenario fleetAt(SimProperties.Scenario.Fleet fleet) {
        return new SimProperties.Scenario(5, 1000, 20260928L, 10, 0.25, Map.of("DAWN", 1), null, "22:58", fleet, null);
    }

    /** 이 테스트의 ops-api — 테스트마다 새로. */
    private FakeOpsClient ops = new FakeOpsClient();

    /** 취소 호출 — 「그때 ops 커맨드가 몇 개였나」와 함께 적는다. 순서를 보는 자리다. */
    private final List<String> cancelCalls = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** 켜졌는지를 기억하는 피드. 순서를 보는 테스트가 쓴다. */
    private static final class RecordingFeed implements RouteFeed {

        private boolean opened;

        @Override
        public void open() {
            opened = true;
        }

        @Override
        public void close() {
            // 닫을 것이 없다.
        }
    }

    private static DriverScenario driverScenario(RouteFeed feed) {
        // 라우트가 오지 않으므로 스캔도 나가지 않는다. 나간다면 그것이 결함이다.
        ScanClient neverCalled = (routeId, call) -> ScanClient.Response.transportFailure("호출되지 않아야 한다");
        DriverTally tally = new DriverTally();
        DriverFleet fleet = new DriverFleet(0, new DriverSimulator(Jitter.NONE), neverCalled,
                () -> 0.0, nanos -> { }, FROZEN_CLOCK, 0L, tally);
        return new DriverScenario(feed, fleet, tally, 0, Duration.ofSeconds(1),
                new DepartureGate(fleet, () -> 0L, nanos -> { }, FROZEN_CLOCK, Duration.ofSeconds(1)));
    }

    private ScenarioRunner runner(SimProperties properties, OrderClient client) {
        return runner(properties, client, RouteFeed.NONE);
    }

    private ScenarioRunner runner(SimProperties properties, OrderClient client, RouteFeed feed) {
        SmokeScenario smoke = new SmokeScenario(client, nanos -> { }, FROZEN_CLOCK);
        return new ScenarioRunner(properties, smoke, driverScenario(feed),
                seed -> RandomGeneratorFactory.of("L64X128MixRandom").create(seed),
                () -> "run-fixed", new WindowStart(AT_2240_KST, nanos -> { }),
                new PeakFleet(ops, AT_2240_KST, nanos -> { }, Duration.ofSeconds(5), Duration.ofSeconds(60),
                        Duration.ofSeconds(20), () -> "T3ST01"),
                (cancel, random) -> new com.dawnline.sim.order.OrderCancellations(
                        new com.dawnline.sim.order.CancelPlan(cancel, random), orderId -> {
                            boolean published = ops.commands.contains("CLOSE_WAVE");
                            cancelCalls.add(published ? "after-close" : "before-close");
                            return published ? OrderClient.Response.of(409, "order-not-cancellable")
                                    : OrderClient.Response.of(200, null);
                        }, nanos -> { }, FROZEN_CLOCK, cancel.ratePerSecond()));
    }

    @Test
    void 계획_전_취소는_접수_직후에_발행_뒤_취소는_계획이_끝난_뒤에_보낸다() throws InterruptedException {
        // 7-4 turbulent — 계획 전은 창 안(웨이브가 닫히기 전), 발행 뒤는 계획이 끝난 뒤다. 발행 뒤의 409 는 실패가 아니라 측정이다(A18 의 창).
        ops.waveAnswers.add(List.of(FakeOpsClient.wave(CUTOFF, "OPEN", null, null)));
        ops.waveAnswers.add(List.of(FakeOpsClient.wave(CUTOFF, "PLANNED", 3, CUTOFF.plusSeconds(90))));
        ops.feasibility = waveId -> FakeOpsClient.assessment(
                FakeOpsClient.line("일반", "SHORTFALL", 1, FakeOpsClient.van("seed", true)));
        ops.threeRoutes();
        ScenarioRunner runner = runner(properties("turbulent", "jwt"),
                (order, key) -> OrderClient.Response.accepted(java.util.UUID.randomUUID()));

        runner.run();

        com.dawnline.sim.order.CancelReport report = runner.lastCancelReport();
        assertThat(report).isNotNull();
        assertThat(report.beforePlanDecided() + report.afterPublishDecided())
                .as("비율 1.0 — DAWN 주문 전부가 둘 중 하나로 뽑혔다").isEqualTo(6);
        assertThat(report.beforePlanDecided()).as("전제 — 두 때가 다 있다").isPositive();
        assertThat(report.afterPublishDecided()).as("전제 — 두 때가 다 있다").isPositive();
        assertThat(cancelCalls.subList(0, report.beforePlanDecided())).as("계획 전 취소는 마감 전에 나갔다")
                .containsOnly("before-close");
        assertThat(cancelCalls.subList(report.beforePlanDecided(), cancelCalls.size())).as("발행 뒤 취소는 계획이 끝난 뒤에 나갔다")
                .hasSize(report.afterPublishDecided()).containsOnly("after-close");
        assertThat(report.afterPublish()).containsEntry("409 order-not-cancellable", report.afterPublishDecided());
        assertThat(report.notSent()).isZero();
    }

    // --- 함대 단계 (ADR-067) ---------------------------------------------------------------------------------

    /** 창(22:58) 뒤의 DAWN 컷오프 — 2026-09-28 00:00 KST. */
    private static final Instant CUTOFF = Instant.parse("2026-09-27T15:00:00Z");

    @Test
    void 함대_단계가_있는데_운영자_토큰이_없으면_보내지_않고_실패한다() throws InterruptedException {
        List<String> keys = new ArrayList<>();
        ScenarioRunner runner = runner(properties("peak"), (order, key) -> {
            keys.add(key);
            return OrderClient.Response.of(201, null);
        });

        runner.run();

        assertThat(runner.getExitCode()).isEqualTo(ScenarioRunner.FAILURE_EXIT_CODE);
        assertThat(keys).isEmpty();
        assertThat(ops.campsCalls).as("전제를 볼 수 없다 — 부르지도 않는다").isZero();
    }

    @Test
    void 앞_실행이_남긴_활성_peak_sim_이_있으면_창을_기다리지_않고_실패한다() throws InterruptedException {
        ops.vehicles.put(FakeOpsClient.CAMP, List.of(FakeOpsClient.van("peak-sim", true)));
        List<String> keys = new ArrayList<>();
        ScenarioRunner runner = runner(properties("overload", "jwt"), (order, key) -> {
            keys.add(key);
            return OrderClient.Response.of(201, null);
        });

        runner.run();

        assertThat(runner.getExitCode()).isEqualTo(ScenarioRunner.FAILURE_EXIT_CODE);
        assertThat(keys).as("남은 차량이 섞인 overload-day 는 과부하가 아니다 — 보내지 않는다").isEmpty();
        assertThat(runner.lastFleetReport()).isNotNull();
        assertThat(runner.lastFleetReport().failures()).singleElement().asString().contains("전제(시작 전)");
    }

    @Test
    void 창이_끝나면_증차하고_계획을_기다려_비활성화한_뒤_리포트_머리를_낸다() throws InterruptedException {
        ops.waveAnswers.add(List.of(FakeOpsClient.wave(CUTOFF, "OPEN", null, null)));
        ops.waveAnswers.add(List.of(FakeOpsClient.wave(CUTOFF, "PLANNED", 3, CUTOFF.plusSeconds(90))));
        ops.feasibility = waveId -> FakeOpsClient.assessment(
                FakeOpsClient.line("일반", "SHORTFALL", 2, FakeOpsClient.van("seed", true)));
        ops.threeRoutes();
        ScenarioRunner runner = runner(properties("peak", "jwt"), (order, key) -> OrderClient.Response.of(201, null));

        runner.run();

        assertThat(runner.getExitCode()).isZero();
        assertThat(ops.commands).containsExactly("ADD_VEHICLE", "ADD_VEHICLE", "CLOSE_WAVE", "REASSIGN_STOP");
        assertThat(ops.addedBodies).hasSize(2);
        assertThat(ops.deactivateCalls).hasSize(2);
        FleetReport report = runner.lastFleetReport();
        assertThat(report).isNotNull();
        assertThat(report.isSuccess()).isTrue();
        assertThat(report.audit()).allSatisfy(line -> assertThat(line.matches()).as(line.action().name()).isTrue());
        assertThat(report.waves()).singleElement().satisfies(wave -> {
            assertThat(wave.added()).isEqualTo(2);
            assertThat(wave.closedAt()).isEqualTo(CUTOFF.plusSeconds(90));
        });
    }

    @Test
    void 창_뒤의_DAWN_컷오프는_다음_날_00시_KST_다() {
        assertThat(ScenarioRunner.dawnCutoffAfter(Instant.parse("2026-09-27T13:58:00Z"))).isEqualTo(CUTOFF);
    }

    @Test
    void 시간_예산을_넘겨도_더한_차량은_비활성화하고_실패로_끝낸다() throws InterruptedException {
        // 마감이 22:00 KST — 증차(22:40 KST, 이 테스트의 시계)보다 앞이다.
        ops.waveAnswers.add(List.of(FakeOpsClient.wave(CUTOFF, "OPEN", null, null)));
        ops.waveAnswers.add(List.of(FakeOpsClient.wave(CUTOFF, "PLANNED", 3, Instant.parse("2026-09-27T13:00:00Z"))));
        ops.feasibility = waveId -> FakeOpsClient.assessment(
                FakeOpsClient.line("일반", "SHORTFALL", 1, FakeOpsClient.van("seed", true)));
        ScenarioRunner runner = runner(properties("peak", "jwt"), (order, key) -> OrderClient.Response.of(201, null));

        runner.run();

        assertThat(runner.getExitCode()).isEqualTo(ScenarioRunner.FAILURE_EXIT_CODE);
        assertThat(ops.deactivateCalls).as("정리는 실패 뒤에도 돈다 — 남으면 다음 실행에 섞인다").hasSize(1);
        assertThat(runner.lastFleetReport().failures()).singleElement().asString().contains("시간 예산");
    }

    @Test
    void 전부_접수되면_종료_코드가_0_이다() throws InterruptedException {
        ScenarioRunner runner = runner(properties("smoke"),
                (order, key) -> OrderClient.Response.of(201, null));

        runner.run();

        assertThat(runner.getExitCode()).isZero();
        ScenarioReport report = runner.lastReport();
        assertThat(report).isNotNull();
        assertThat(report.accepted()).isEqualTo(5);
    }

    @Test
    void 한_건이라도_실패하면_0_이_아닌_코드로_끝난다() throws InterruptedException {
        // make demo 같은 스크립트가 "성공" 이라고 말한 뒤 DB 가 비어 있는 상황을 막는다.
        int[] call = {0};
        ScenarioRunner runner = runner(properties("smoke"),
                (order, key) -> call[0]++ == 0
                        ? OrderClient.Response.of(422, "tier-not-serviceable")
                        : OrderClient.Response.of(201, null));

        runner.run();

        assertThat(runner.getExitCode()).isEqualTo(ScenarioRunner.FAILURE_EXIT_CODE);
        ScenarioReport report = runner.lastReport();
        assertThat(report).isNotNull();
        assertThat(report.problemCodes()).containsEntry("tier-not-serviceable", 1);
    }

    @Test
    void 창이_앞에_있으면_기다린_뒤_보낸다() throws InterruptedException {
        List<String> keys = new ArrayList<>();
        ScenarioRunner runner = runner(properties("window-ahead"), (order, key) -> {
            keys.add(key);
            return OrderClient.Response.of(201, null);
        });

        runner.run();

        assertThat(runner.getExitCode()).isZero();
        assertThat(keys).hasSize(5);
    }

    @Test
    void 창을_이미_지났으면_한_건도_보내지_않고_실패한다() throws InterruptedException {
        // 22:40 에 22:30 창 — 다음 창은 23시간 50분 뒤다. 늦게 시작한 한 시간은 컷오프를 넘어 창의 수를 바꾼다.
        List<String> keys = new ArrayList<>();
        ScenarioRunner runner = runner(properties("window-missed"), (order, key) -> {
            keys.add(key);
            return OrderClient.Response.of(201, null);
        });

        runner.run();

        assertThat(runner.getExitCode()).isEqualTo(ScenarioRunner.FAILURE_EXIT_CODE);
        assertThat(keys).isEmpty();
        assertThat(runner.lastReport()).isNull();
    }

    @Test
    void 없는_시나리오는_있는_이름을_함께_알려_준다() {
        ScenarioRunner runner = runner(properties("smok"),
                (order, key) -> OrderClient.Response.of(201, null));

        assertThatThrownBy(runner::run)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("smok")
                .hasMessageContaining("smoke");
    }

    @Test
    void 실행_식별자가_멱등_키_접두어가_된다() throws InterruptedException {
        List<String> keys = new ArrayList<>();
        ScenarioRunner runner = runner(properties("smoke"), (order, key) -> {
            keys.add(key);
            return OrderClient.Response.of(201, null);
        });

        runner.run();

        assertThat(keys).allSatisfy(key -> assertThat(key).startsWith("run-fixed-"));
    }

    @Test
    void 실행_전에는_보고할_결과가_없다() {
        assertThat(runner(properties("smoke"),
                (order, key) -> OrderClient.Response.of(201, null)).lastReport()).isNull();
    }

    @Test
    void 기사_시나리오는_주문보다_먼저_수신을_켠다() throws InterruptedException {
        // 뒤에 켜면 그 사이에 확정된 라우트를 영영 놓친다(auto-offset-reset: latest).
        // 그리고 놓친 것은 "라우트가 안 왔다" 로 보여서 웨이브가 안 닫힌 것과 구별되지 않는다.
        RecordingFeed feed = new RecordingFeed();
        List<Boolean> feedWasOpen = new ArrayList<>();
        ScenarioRunner runner = runner(properties("with-driver"), (order, key) -> {
            feedWasOpen.add(feed.opened);
            return OrderClient.Response.of(201, null);
        }, feed);

        runner.run();

        assertThat(feedWasOpen).isNotEmpty().allMatch(open -> open);
    }

    @Test
    void 기사를_쓰지_않는_시나리오는_수신을_켜지_않는다() throws InterruptedException {
        RecordingFeed feed = new RecordingFeed();

        runner(properties("smoke"), (order, key) -> OrderClient.Response.of(201, null), feed).run();

        assertThat(feed.opened).isFalse();
    }

    @Test
    void 기사_시나리오는_기사_결과도_보고한다() throws InterruptedException {
        ScenarioRunner runner = runner(properties("with-driver"),
                (order, key) -> OrderClient.Response.of(201, null));

        runner.run();

        DriverReport report = runner.lastDriverReport();
        assertThat(report).isNotNull();
        assertThat(report.scenario()).isEqualTo("with-driver");
    }
}
