package com.dawnline.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.sim.config.SimProperties;
import com.dawnline.sim.order.OrderClient;
import com.dawnline.sim.order.ScenarioReport;
import com.dawnline.sim.order.SmokeScenario;
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
            5, 1000, 20260904L, 10, 0.25, Map.of("DAWN", 1));

    private static final LongSupplier FROZEN_CLOCK = () -> 1_000_000_000L;

    private static SimProperties properties(String selected) {
        return new SimProperties(selected, "http://localhost:8081", 5000, Map.of("smoke", SMOKE));
    }

    private static ScenarioRunner runner(SimProperties properties, OrderClient client) {
        SmokeScenario smoke = new SmokeScenario(client, nanos -> { }, FROZEN_CLOCK);
        return new ScenarioRunner(properties, smoke,
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
}
