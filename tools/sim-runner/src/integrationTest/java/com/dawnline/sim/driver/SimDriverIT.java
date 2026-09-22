package com.dawnline.sim.driver;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Ids;
import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.contract.EventContracts;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.sim.ScenarioRunner;
import com.dawnline.sim.SimRunnerApplication;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;

/**
 * 기사 시뮬레이터 전 구간 — 브로커의 {@code route.assigned} 에서 tracking 의 스캔 API 까지
 * (IMPLEMENTATION_PLAN Phase 5-2).
 *
 * <h2>경합을 없앤 자리</h2>
 * 라우트는 <strong>주문 스텁이 요청을 받은 순간</strong> 발행한다. 시뮬레이터는 주문을 넣기
 * 전에 수신을 켜고 파티션 배정까지 기다리므로({@code KafkaRouteFeed}), 그 시점에 발행된
 * 이벤트는 반드시 보인다 — "가끔 통과하는" 테스트를 만들지 않으려면 순서가 우연이 아니어야 한다.
 *
 * <h2>픽스처가 계약을 지킨다</h2>
 * 발행하는 봉투를 {@link EventContracts} 로 그 자리에서 검증한다(불변규칙 8). 검증하지 않으면
 * 「시뮬레이터는 도는데 <em>실제</em> {@code route.assigned} 로는 안 되는」 상태를 만들 수 있다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SimDriverIT {

    /** deploy/compose/.env.example 의 {@code KAFKA_IMAGE} 와 같은 태그. */
    static final String KAFKA_IMAGE = "apache/kafka:4.3.1";

    private static final String TOPIC = "dawnline.route.assigned.v1";

    /**
     * 자동 토픽 생성을 끈다 — Compose 의 브로커와 같은 설정이다. 켜 두면 토픽 이름 오타가
     * 테스트에서는 조용히 통과한다.
     */
    private static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE)
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    /**
     * 계획 출발 시각. 리터럴로 적지 않는다 — 벽시계와 비교되는 경로는 없지만, 날짜를 박아 두면
     * 그 테스트에 유효 기간이 생긴다 (CLAUDE.md 코딩 컨벤션).
     */
    private static final Instant DEPARTURE = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private static final UUID ROUTE_ID = Ids.newId();

    /** stop 마다 계획 도착이 출발 뒤 몇 분인가. */
    private static final int[] ARRIVAL_MINUTES = {20, 45};

    private static final int SERVICE_SECONDS = 300;

    /** 스텁이 받은 스캔. */
    private record Scan(String path, JsonNode body) {
    }

    private static final List<Scan> SCANS = new ArrayList<>();

    private static HttpServer stack;
    private static KafkaProducer<String, String> producer;
    private static ConfigurableApplicationContext context;
    private static ScenarioRunner runner;

    @BeforeAll
    static void runScenarioOnce() throws IOException, InterruptedException, ExecutionException {
        KAFKA.start();
        createTopic();
        producer = new KafkaProducer<>(producerConfig());

        stack = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // order-service 스텁. 첫 주문을 받은 순간 라우트를 발행한다 — 그 시점이면 시뮬레이터는
        // 이미 파티션을 배정받고 기다리는 중이다(클래스 javadoc).
        stack.createContext("/api/v1/orders", exchange -> {
            drain(exchange);
            publishRoute();
            respond(exchange, 201, "{}");
        });
        // tracking 스캔 API 스텁.
        stack.createContext("/api/v1/routes", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (SCANS) {
                SCANS.add(new Scan(exchange.getRequestURI().getPath(),
                        EventJson.standardMapper().readTree(body)));
            }
            respond(exchange, 200, """
                    {"routeId":"%s","stopSeq":1,"type":"ARRIVED","orders":[
                      {"orderId":"%s","outcome":"APPLIED","status":"ARRIVED"}]}
                    """.formatted(ROUTE_ID, Ids.newId()));
        });
        stack.start();

        // 시나리오 한 판. run() 은 CommandLineRunner 가 끝난 뒤에 돌아온다.
        context = run();
        runner = context.getBean(ScenarioRunner.class);
    }

    @AfterAll
    static void stopStack() {
        if (context != null) {
            context.close();
        }
        if (stack != null) {
            stack.stop(0);
        }
        if (producer != null) {
            producer.close(Duration.ofSeconds(5));
        }
        KAFKA.stop();
    }

    @Test
    void 브로커의_라우트를_끝까지_돈다() {
        DriverReport report = runner.lastDriverReport();

        assertThat(report).isNotNull();
        assertThat(report.routes()).isEqualTo(1);
        assertThat(report.completedRoutes()).isEqualTo(1);
        assertThat(report.abandonedRoutes()).isZero();
        assertThat(report.failures()).isEmpty();
        assertThat(report.isSuccess()).isTrue();
        assertThat(runner.getExitCode()).isZero();
    }

    @Test
    void 스캔이_계약대로_나간다() {
        // 캠프 출발 하나 + stop 마다 둘. TrackingPublishIT 이 고정한 팬아웃과 같은 셈이다.
        assertThat(scans()).hasSize(1 + 2 * ARRIVAL_MINUTES.length);
        assertThat(scans()).extracting(scan -> scan.body().path("type").stringValue())
                .containsExactly("DEPARTED_CAMP", "ARRIVED", "COMPLETED", "ARRIVED", "COMPLETED");
        assertThat(scans()).allSatisfy(scan ->
                assertThat(scan.path()).startsWith("/api/v1/routes/" + ROUTE_ID + "/stops/"));
    }

    @Test
    void 주입한_지연이_스캔의_시각에_그대로_실린다() {
        // 이 IT 의 요점이다. occurredAt 이 벽시계였다면 "늦었다" 를 값으로 확인할 방법이 없고,
        // late-injection 시나리오는 아무것도 어설션하지 못한다.
        assertThat(scans()).isNotEmpty();

        Instant departed = occurredAt(scans().getFirst());
        assertThat(departed).isAfterOrEqualTo(DEPARTURE)
                .isBeforeOrEqualTo(DEPARTURE.plus(Duration.ofMinutes(30)));

        for (int stop = 0; stop < ARRIVAL_MINUTES.length; stop++) {
            Instant plannedArrival = DEPARTURE.plus(Duration.ofMinutes(ARRIVAL_MINUTES[stop]));
            Instant actualArrival = occurredAt(scans().get(1 + stop * 2));
            // delay-probability=1.0 이므로 모든 구간이 늦는다. 그 차이가 tracking 이 계산하는 편차다.
            assertThat(actualArrival).as("stop %d 의 도착은 계획보다 늦어야 한다", stop + 1)
                    .isAfter(plannedArrival);
            // 완료는 도착 + 체류다.
            assertThat(occurredAt(scans().get(2 + stop * 2)))
                    .isEqualTo(actualArrival.plusSeconds(SERVICE_SECONDS));
        }
    }

    @Test
    void 컨텍스트는_DB_없이_뜬다() {
        // libs/messaging 을 끌어오면서 JPA 를 빼는 결정(build.gradle.kts)이 지켜지는 자리다.
        // 남아 있으면 DataSourceAutoConfiguration 이 "url 이 없다" 로 기동을 막는다.
        assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
    }

    // -------------------------------------------------------------------------

    private static List<Scan> scans() {
        synchronized (SCANS) {
            return List.copyOf(SCANS);
        }
    }

    private static Instant occurredAt(Scan scan) {
        return Instant.parse(scan.body().path("occurredAt").stringValue());
    }

    private static ConfigurableApplicationContext run() {
        String baseUrl = "http://127.0.0.1:" + stack.getAddress().getPort();
        return new SpringApplicationBuilder(SimRunnerApplication.class).run(
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--spring.kafka.consumer.group-id=sim-it-" + UUID.randomUUID(),
                "--dawnline.sim.scenario=it",
                "--dawnline.sim.base-url=" + baseUrl,
                "--dawnline.sim.scenarios.it.orders=1",
                "--dawnline.sim.scenarios.it.rate-per-second=1",
                "--dawnline.sim.scenarios.it.seed=20260919",
                "--dawnline.sim.scenarios.it.customers=1",
                "--dawnline.sim.scenarios.it.cold-ratio=0.0",
                "--dawnline.sim.scenarios.it.tier-weights.DAWN=1",
                "--dawnline.sim.scenarios.it.driver.routes=1",
                // 배속을 끈다. 이 테스트가 보는 것은 시각의 계산이지 대기가 아니다.
                "--dawnline.sim.scenarios.it.driver.speed=0",
                "--dawnline.sim.scenarios.it.driver.timeout-seconds=60",
                "--dawnline.sim.scenarios.it.driver.scan-retry-seconds=10",
                "--dawnline.sim.scenarios.it.driver.scan-base-url=" + baseUrl,
                "--dawnline.sim.scenarios.it.driver.delay-probability=1.0",
                "--dawnline.sim.scenarios.it.driver.delay-magnitude=0.5",
                "--dawnline.sim.scenarios.it.driver.departure-delay-seconds=1800");
    }

    /** 계약에 맞는 {@code route.assigned} 를 하나 발행한다. */
    private static void publishRoute() {
        EventEnvelope<Map<String, Object>> envelope = new EventEnvelope<>(Ids.newId(), "route.assigned", 1,
                DEPARTURE, "dispatch-service", ROUTE_ID.toString(), null, payload());
        String json = EventJson.standardMapper().writeValueAsString(envelope);

        // 픽스처가 계약과 어긋나면 여기서 멈춘다 (불변규칙 8).
        EventContracts.load().validateRecord(json);

        producer.send(new ProducerRecord<>(TOPIC, ROUTE_ID.toString(), json));
        producer.flush();
    }

    private static Map<String, Object> payload() {
        List<Map<String, Object>> stops = new ArrayList<>();
        for (int index = 0; index < ARRIVAL_MINUTES.length; index++) {
            Instant arrival = DEPARTURE.plus(Duration.ofMinutes(ARRIVAL_MINUTES[index]));
            Map<String, Object> stop = new LinkedHashMap<>();
            stop.put("seq", index + 1);
            stop.put("orderIds", List.of(Ids.newId().toString()));
            stop.put("lat", 37.5 + index * 0.01);
            stop.put("lng", 127.0 + index * 0.01);
            stop.put("plannedArrival", arrival.toString());
            stop.put("serviceSeconds", SERVICE_SECONDS);
            // 시뮬레이터는 읽지 않지만 계약에서 required 다 (Phase 5-1a).
            stop.put("promisedWindow", Map.of(
                    "start", arrival.minus(Duration.ofMinutes(30)).toString(),
                    "end", arrival.plus(Duration.ofMinutes(30)).toString()));
            stops.add(stop);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("stopCount", stops.size());
        summary.put("distanceM", 12000);
        summary.put("durationS", 3600);
        summary.put("costKrw", 45000);
        summary.put("plannedDeparture", DEPARTURE.toString());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("routeId", ROUTE_ID.toString());
        payload.put("planId", Ids.newId().toString());
        payload.put("waveId", Ids.newId().toString());
        payload.put("campId", Ids.newId().toString());
        payload.put("vehicleId", Ids.newId().toString());
        payload.put("driverId", Ids.newId().toString());
        payload.put("strategy", "sweep-greedy-nn+ls");
        payload.put("revision", 1);
        payload.put("summary", summary);
        payload.put("stops", stops);
        return payload;
    }

    private static void createTopic() throws InterruptedException, ExecutionException {
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            try {
                admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
            } catch (ExecutionException exception) {
                if (!(exception.getCause() instanceof TopicExistsException)) {
                    throw exception;
                }
            }
        }
    }

    private static Properties producerConfig() {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        return config;
    }

    private static void drain(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
