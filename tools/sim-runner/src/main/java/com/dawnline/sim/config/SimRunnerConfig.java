package com.dawnline.sim.config;

import com.dawnline.sim.ScenarioRunner;
import com.dawnline.sim.order.HttpOrderClient;
import com.dawnline.sim.order.OrderClient;
import com.dawnline.sim.order.Sleeper;
import com.dawnline.sim.order.SmokeScenario;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * 배선. 시간·난수는 <strong>여기서만</strong> 만든다 (불변규칙 12).
 *
 * <p>구성 루트가 시간과 난수의 출처를 정하고, 나머지는 전부 주입받는다. 이 파일 밖에서
 * {@code System.nanoTime()} 이나 {@code new Random()} 이 보이면 그것은 결함이다 — 그 순간부터
 * 시나리오를 재현할 수 없다.
 */
@Configuration(proxyBeanMethods = false)
public class SimRunnerConfig {

    /** 커넥션 수립 타임아웃. 요청 타임아웃은 {@code dawnline.sim.request-timeout-ms} 다. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** 난수 알고리즘. 이름으로 고정한다 — 아래 주석 참고. */
    private static final String ALGORITHM = "L64X128MixRandom";

    /**
     * 주문 접수 클라이언트.
     *
     * @param properties 설정
     */
    @Bean
    public OrderClient orderClient(SimProperties properties) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        return new HttpOrderClient(http, JsonMapper.builder().build(),
                properties.baseUrl(), Duration.ofMillis(properties.requestTimeoutMs()));
    }

    /**
     * smoke 시나리오.
     *
     * @param client 주문 접수 클라이언트
     */
    @Bean
    public SmokeScenario smokeScenario(OrderClient client) {
        LongSupplier nanoTime = System::nanoTime;
        return new SmokeScenario(client, Sleeper.REAL, nanoTime);
    }

    /**
     * seed → 난수원.
     *
     * <p>{@code L64X128MixRandom} 은 JDK 17 부터의 {@code RandomGenerator} 구현이다.
     * {@code java.util.Random} 과 달리 <em>알고리즘 이름으로</em> 고정되므로, JDK 가 올라가도
     * 같은 seed 가 같은 수열을 낸다 — 시나리오 재현이 JDK 버전에 매달리지 않는다.
     */
    @Bean
    public ScenarioRunner.RandomGeneratorFactory randomGeneratorFactory() {
        java.util.random.RandomGeneratorFactory<RandomGenerator> factory =
                java.util.random.RandomGeneratorFactory.of(ALGORITHM);
        return factory::create;
    }

    /** 실행 식별자. 멱등 키가 실행 간에 겹치면 두 번째 실행이 전부 200 재생이 된다. */
    @Bean
    public ScenarioRunner.RunIds runIds() {
        return () -> "sim-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
