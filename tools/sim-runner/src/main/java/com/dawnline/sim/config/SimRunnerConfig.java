package com.dawnline.sim.config;

import com.dawnline.messaging.json.EventJson;
import com.dawnline.sim.ScenarioRunner;
import com.dawnline.sim.config.SimProperties.Scenario;
import com.dawnline.sim.config.SimProperties.Scenario.Driver;
import com.dawnline.sim.driver.DriverFleet;
import com.dawnline.sim.driver.DriverScenario;
import com.dawnline.sim.driver.DriverSimulator;
import com.dawnline.sim.driver.DriverTally;
import com.dawnline.sim.driver.HttpScanClient;
import com.dawnline.sim.driver.Jitter;
import com.dawnline.sim.driver.KafkaRouteFeed;
import com.dawnline.sim.driver.RouteAssignedListener;
import com.dawnline.sim.driver.RouteFeed;
import com.dawnline.sim.driver.ScanClient;
import com.dawnline.sim.driver.SeededJitter;
import com.dawnline.sim.order.HttpOrderClient;
import com.dawnline.sim.order.OrderClient;
import com.dawnline.sim.order.Sleeper;
import com.dawnline.sim.order.SmokeScenario;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
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

    /** 파티션 배정을 기다리는 상한. 브로커가 없으면 여기서 끝난다 ({@link KafkaRouteFeed}). */
    private static final Duration ASSIGNMENT_TIMEOUT = Duration.ofSeconds(30);

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

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

    // -------------------------------------------------------------------------
    // 기사 시뮬레이터 (Phase 5-2)
    //
    // 기사 설정이 없는 시나리오에서도 빈은 전부 만들어진다. 대신 아무 일도 하지 않는다 —
    // 피드는 RouteFeed.NONE 이고 기다릴 라우트는 0 이라 smoke 는 브로커 없이 그대로 돈다.
    // 조건부 빈으로 나누면 "어떤 시나리오를 골랐나" 가 배선 조건이 되어, 시나리오 이름 오타가
    // 컨텍스트 기동 실패로 나타난다.
    // -------------------------------------------------------------------------

    /** 기사들이 함께 채우는 집계. */
    @Bean
    public DriverTally driverTally() {
        return new DriverTally();
    }

    /**
     * 스캔 API 클라이언트.
     *
     * <p>매퍼는 {@link EventJson#standardMapper()} 를 쓴다. 스캔 본문은 이벤트가 아니지만 필요한
     * 설정이 똑같고(ISO-8601 시각 · null 필드 생략 · 모르는 필드 무시) 그 메서드는 도구가
     * 재사용하라고 공개된 것이다. 같은 설정을 여기 다시 적으면 한쪽만 바뀔 자리가 생긴다.
     *
     * @param properties 설정
     */
    @Bean
    public ScanClient scanClient(SimProperties properties) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        return new HttpScanClient(http, EventJson.standardMapper(), scanBaseUrl(properties),
                Duration.ofMillis(properties.requestTimeoutMs()));
    }

    /**
     * 순수 시뮬레이터. 지연·실패는 시나리오 seed 에서 나온다 (불변규칙 12).
     *
     * @param properties    설정
     * @param randomFactory seed → 난수원
     */
    @Bean
    public DriverSimulator driverSimulator(SimProperties properties,
            ScenarioRunner.RandomGeneratorFactory randomFactory) {
        Scenario scenario = properties.selectedOrNull();
        Driver driver = scenario == null ? null : scenario.driver();
        if (scenario == null || driver == null) {
            return new DriverSimulator(Jitter.NONE);
        }
        return new DriverSimulator(new SeededJitter(scenario.seed(), randomFactory::create,
                driver.delayProbability(), driver.delayMagnitude(), driver.failureProbability(),
                driver.departureDelaySeconds()));
    }

    /**
     * 기사들.
     *
     * @param properties 설정
     * @param simulator  순수 시뮬레이터
     * @param scans      스캔 API
     * @param tally      집계
     */
    @Bean
    public DriverFleet driverFleet(SimProperties properties, DriverSimulator simulator, ScanClient scans,
            DriverTally tally) {
        Driver driver = driverOf(properties);
        int routes = driver == null ? 0 : driver.routes();
        long retryNanos = driver == null ? 0L : driver.scanRetrySeconds() * NANOS_PER_SECOND;
        double speed = driver == null ? 0.0 : driver.speed();
        return new DriverFleet(routes, simulator, scans, () -> speed, Sleeper.REAL, System::nanoTime,
                retryNanos, tally);
    }

    /**
     * {@code route.assigned} 리스너. 컨테이너는 꺼진 채로 등록된다
     * ({@link RouteAssignedListener} 의 {@code autoStartup=false}).
     *
     * @param fleet 기사들
     * @param json  이벤트 JSON 코덱 (libs/messaging 자동설정)
     */
    @Bean
    public RouteAssignedListener routeAssignedListener(DriverFleet fleet, EventJson json) {
        return new RouteAssignedListener(fleet, json);
    }

    /**
     * 수신 스위치. 기사를 쓰지 않는 시나리오는 아무것도 받지 않는다.
     *
     * @param properties 설정
     * @param registry   리스너 레지스트리 (Kafka 자동설정)
     */
    @Bean
    public RouteFeed routeFeed(SimProperties properties, ObjectProvider<KafkaListenerEndpointRegistry> registry) {
        if (driverOf(properties) == null) {
            return RouteFeed.NONE;
        }
        KafkaListenerEndpointRegistry found = registry.getIfAvailable();
        if (found == null) {
            throw new IllegalStateException(
                    "Kafka 리스너 레지스트리가 없습니다. 기사 시뮬레이터는 route.assigned 를 구독합니다");
        }
        return new KafkaRouteFeed(found, RouteAssignedListener.LISTENER_ID, ASSIGNMENT_TIMEOUT,
                Sleeper.REAL, System::nanoTime);
    }

    /**
     * 기사 시뮬레이션 한 판.
     *
     * @param properties 설정
     * @param feed       수신 스위치
     * @param fleet      기사들
     * @param tally      집계
     */
    @Bean
    public DriverScenario driverScenario(SimProperties properties, RouteFeed feed, DriverFleet fleet,
            DriverTally tally) {
        Driver driver = driverOf(properties);
        return new DriverScenario(feed, fleet, tally,
                driver == null ? 0 : driver.routes(),
                Duration.ofSeconds(driver == null ? 1 : driver.timeoutSeconds()));
    }

    /** 고른 시나리오의 기사 설정. 없으면 {@code null}. */
    private static @Nullable Driver driverOf(SimProperties properties) {
        Scenario scenario = properties.selectedOrNull();
        return scenario == null ? null : scenario.driver();
    }

    /**
     * 스캔 API 주소.
     *
     * <p>기사 설정이 없으면 주문 주소를 그대로 쓴다. 어차피 호출되지 않으므로 값은 상관없지만,
     * 여기에 기본 주소를 한 벌 더 적으면 {@code Driver} 의 {@code @DefaultValue} 와 갈라진다.
     */
    private static String scanBaseUrl(SimProperties properties) {
        Driver driver = driverOf(properties);
        return driver == null ? properties.baseUrl() : driver.scanBaseUrl();
    }
}
