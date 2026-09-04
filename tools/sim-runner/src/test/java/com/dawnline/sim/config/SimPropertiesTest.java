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
        assertThatThrownBy(() -> new SimProperties.Scenario(0, 20, 1L, 10, 0.25, Map.of("DAWN", 1)))
                .hasMessageContaining("orders");
        assertThatThrownBy(() -> new SimProperties.Scenario(10, 0, 1L, 10, 0.25, Map.of("DAWN", 1)))
                .hasMessageContaining("rate-per-second");
        assertThatThrownBy(() -> new SimProperties.Scenario(10, 20, 1L, 0, 0.25, Map.of("DAWN", 1)))
                .hasMessageContaining("customers");
        assertThatThrownBy(() -> new SimProperties.Scenario(10, 20, 1L, 10, 1.5, Map.of("DAWN", 1)))
                .hasMessageContaining("cold-ratio");
        assertThatThrownBy(() -> new SimProperties.Scenario(10, 20, 1L, 10, 0.25, Map.of()))
                .hasMessageContaining("tier-weights");
        assertThatThrownBy(() -> new SimProperties.Scenario(10, 20, 1L, 10, 0.25, Map.of("DAWN", 0)))
                .hasMessageContaining("합이 0");
    }

    @Test
    void 타임아웃은_1ms_미만일_수_없다() {
        assertThatThrownBy(() -> new SimProperties("smoke", "http://x", 0, Map.of()))
                .hasMessageContaining("request-timeout-ms");
    }
}
