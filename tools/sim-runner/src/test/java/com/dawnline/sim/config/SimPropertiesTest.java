package com.dawnline.sim.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 시나리오 YAML 이 실제로 바인딩되는지 본다.
 *
 * <p>스프링 컨텍스트를 띄우지 않는 이유: 컨텍스트를 띄우면 {@code CommandLineRunner} 가 돌면서
 * 진짜 order-service 에 요청을 보낸다. 여기서 확인하려는 것은 <strong>YAML 이 record 로 들어오는가</strong>
 * 뿐이다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SimPropertiesTest {

    private static SimProperties bindScenariosYml() throws IOException {
        List<PropertySource<?>> loaded =
                new YamlPropertySourceLoader().load("scenarios", new ClassPathResource("scenarios.yml"));
        MutablePropertySources sources = new MutablePropertySources();
        loaded.forEach(sources::addLast);
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("dawnline.sim", SimProperties.class)
                .orElseThrow(() -> new AssertionError("dawnline.sim 을 바인딩하지 못했다"));
    }

    @Test
    void scenarios_yml_의_smoke_는_주문_200건이다() throws IOException {
        SimProperties.Scenario smoke = bindScenariosYml().scenarios().get("smoke");

        assertThat(smoke).isNotNull();
        assertThat(smoke.orders()).isEqualTo(200);
        assertThat(smoke.ratePerSecond()).isEqualTo(20);
        assertThat(smoke.tierWeights()).containsOnlyKeys("DAWN", "SAME_DAY", "NEXT_DAY");
        // 고객당 평균 1건. §7.2 레이트 리밋(용량 60)에 닿을 수 없다.
        assertThat(smoke.orders() / (double) smoke.customers()).isLessThan(60.0);
    }

    @Test
    void scenarios_yml_의_late_injection_은_기사까지_돈다() throws IOException {
        SimProperties.Scenario late = bindScenariosYml().scenarios().get("late-injection");

        assertThat(late).isNotNull();
        SimProperties.Scenario.Driver driver = late.driver();
        assertThat(driver).isNotNull();
        assertThat(driver.routes()).isEqualTo(1);
        assertThat(driver.speed()).isEqualTo(600.0);
        // 늦게 출발하는 것이 가장 흔한 지연 원인이다 (§5.4). 이 시나리오의 이름이 그것이다.
        assertThat(driver.departureDelaySeconds()).isEqualTo(1800);
        assertThat(driver.delayProbability()).isEqualTo(1.0);
        assertThat(driver.delayMagnitude()).isPositive();
    }

    @Test
    void 기사_절이_없는_시나리오는_브로커_없이_돈다() throws IOException {
        // driver 가 null 이면 리스너를 켜지 않는다(ScenarioRunner). smoke 가 Kafka 를 요구하게
        // 되는 순간 Phase 1 의 시나리오가 Phase 5 의 스택을 필요로 한다.
        assertThat(bindScenariosYml().scenarios().get("smoke").driver()).isNull();
    }

    @Test
    void 기사_절이_있는_시나리오는_전부_돌_수_있는_값이다() throws IOException {
        // 이름을 열거하지 않는다 — 새 시나리오가 조용히 검사 밖에 남지 않게(CLAUDE.md 코딩 컨벤션).
        Map<String, SimProperties.Scenario> scenarios = bindScenariosYml().scenarios();

        assertThat(scenarios).isNotEmpty();
        assertThat(scenarios.values()).filteredOn(scenario -> scenario.driver() != null)
                .isNotEmpty()
                .allSatisfy(scenario -> {
                    SimProperties.Scenario.Driver driver = scenario.driver();
                    assertThat(driver).isNotNull();
                    assertThat(driver.scanBaseUrl()).isNotBlank();
                    assertThat(driver.timeoutSeconds()).isPositive();
                    // 라우트가 생기려면 주문이 흘러야 한다. 주문 없는 기사 시나리오는 기다리기만 한다.
                    assertThat(scenario.orders()).isPositive();
                });
    }

    @Test
    void 고른_시나리오가_없으면_배선은_null_을_받는다() throws IOException {
        // selected() 와 달리 던지지 않는다 — 배선에서 던지면 "있는 것: [...]" 안내가
        // 컨텍스트 기동 실패의 스택 아래로 묻힌다.
        SimProperties properties = new SimProperties("없는것", "http://localhost:8081", 5000,
                bindScenariosYml().scenarios());

        assertThat(properties.selectedOrNull()).isNull();
    }

    @Test
    void 기사_설정의_값은_범위_안이어야_한다() {
        assertThatThrownBy(() -> driver(0, 1.0, 0.0, 0.5))
                .hasMessageContaining("driver.routes");
        assertThatThrownBy(() -> driver(1, 1.5, 0.0, 0.5))
                .hasMessageContaining("driver.delay-probability");
        assertThatThrownBy(() -> driver(1, 0.5, 0.0, 1.5))
                .hasMessageContaining("driver.failure-probability");
        assertThatThrownBy(() -> driver(1, 0.5, -0.1, 0.5))
                .hasMessageContaining("driver.delay-magnitude");
    }

    private static SimProperties.Scenario.Driver driver(int routes, double delayProbability,
            double delayMagnitude, double failureProbability) {
        return new SimProperties.Scenario.Driver(routes, 600.0, 300, 30, "http://localhost:8084",
                delayProbability, delayMagnitude, failureProbability, 0);
    }

    @Test
    void 이름이_틀리면_있는_것을_함께_알려_준다() throws IOException {
        SimProperties properties = new SimProperties("없는것", "http://localhost:8081", 5000,
                bindScenariosYml().scenarios());

        assertThatThrownBy(properties::selected)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("없는것")
                .hasMessageContaining("smoke");
    }

    @Test
    void 잘못된_시나리오_값은_만들어지는_순간_거부된다() {
        assertThatThrownBy(() -> new SimProperties.Scenario(0, 20, 1L, 10, 0.25, Map.of("DAWN", 1), null))
                .hasMessageContaining("orders");
        assertThatThrownBy(() -> new SimProperties.Scenario(10, 0, 1L, 10, 0.25, Map.of("DAWN", 1), null))
                .hasMessageContaining("rate-per-second");
        assertThatThrownBy(() -> new SimProperties.Scenario(10, 20, 1L, 0, 0.25, Map.of("DAWN", 1), null))
                .hasMessageContaining("customers");
        assertThatThrownBy(() -> new SimProperties.Scenario(10, 20, 1L, 10, 1.5, Map.of("DAWN", 1), null))
                .hasMessageContaining("cold-ratio");
        assertThatThrownBy(() -> new SimProperties.Scenario(10, 20, 1L, 10, 0.25, Map.of(), null))
                .hasMessageContaining("tier-weights");
        assertThatThrownBy(() -> new SimProperties.Scenario(10, 20, 1L, 10, 0.25, Map.of("DAWN", 0), null))
                .hasMessageContaining("합이 0");
    }

    @Test
    void 타임아웃은_1ms_미만일_수_없다() {
        assertThatThrownBy(() -> new SimProperties("smoke", "http://x", 0, Map.of()))
                .hasMessageContaining("request-timeout-ms");
    }
}
