package com.dawnline.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.sim.config.SimProperties;
import com.dawnline.sim.driver.DriverFleet;
import com.dawnline.sim.driver.DriverReport;
import com.dawnline.sim.driver.DriverScenario;
import com.dawnline.sim.driver.DriverSimulator;
import com.dawnline.sim.driver.DriverTally;
import com.dawnline.sim.driver.Jitter;
import com.dawnline.sim.driver.RouteFeed;
import com.dawnline.sim.driver.ScanClient;
import com.dawnline.sim.order.OrderClient;
import com.dawnline.sim.order.ScenarioReport;
import com.dawnline.sim.order.SmokeScenario;
import java.time.Duration;
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
            5, 1000, 20260904L, 10, 0.25, Map.of("DAWN", 1), null);

    /** 기사까지 도는 시나리오. 기다릴 라우트는 0 이라 대기 없이 끝난다. */
    private static final SimProperties.Scenario WITH_DRIVER = new SimProperties.Scenario(
            3, 1000, 20260904L, 10, 0.25, Map.of("DAWN", 1),
            new SimProperties.Scenario.Driver(1, 0.0, 1, 0, "http://localhost:8084",
                    0.0, 0.0, 0.0, 0));

    private static final LongSupplier FROZEN_CLOCK = () -> 1_000_000_000L;

    private static SimProperties properties(String selected) {
        return new SimProperties(selected, "http://localhost:8081", 5000,
                Map.of("smoke", SMOKE, "with-driver", WITH_DRIVER));
    }

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
        return new DriverScenario(feed, fleet, tally, 0, Duration.ofSeconds(1));
    }

    private static ScenarioRunner runner(SimProperties properties, OrderClient client) {
        return runner(properties, client, RouteFeed.NONE);
    }

    private static ScenarioRunner runner(SimProperties properties, OrderClient client, RouteFeed feed) {
        SmokeScenario smoke = new SmokeScenario(client, nanos -> { }, FROZEN_CLOCK);
        return new ScenarioRunner(properties, smoke, driverScenario(feed),
                seed -> RandomGeneratorFactory.of("L64X128MixRandom").create(seed),
                () -> "run-fixed");
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
