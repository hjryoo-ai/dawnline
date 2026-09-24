package com.dawnline.ops.adapter.out.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.observability.MdcKeys;
import com.dawnline.ops.application.port.in.OpsCommand;
import com.dawnline.ops.application.port.out.CoreCommands;
import com.dawnline.ops.application.port.out.CoreReply;
import com.dawnline.ops.config.CoreClientsConfig;
import com.dawnline.web.internal.InternalToken;
import com.dawnline.web.internal.InternalTokenProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientPropertiesAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.service.HttpServiceClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 위임 어댑터가 코어의 답을 갈래로 옮기는가 — 실제 HTTP 로 (DESIGN.md §5.5 「커맨드 위임」).
 *
 * <p>가짜 코어는 JDK 의 {@link HttpServer} 다(새 라이브러리 없이). 클라이언트는 운영과 같은 배선 —
 * {@link CoreClientsConfig} 의 {@code @ImportHttpServices} 와 Boot 4 의 HTTP Service Client 자동 구성 — 으로
 * 만든다. 그래서 여기서 보는 타임아웃과 헤더는 설정 키({@code spring.http.serviceclient.<그룹>})를 거친 것이다.
 *
 * <p>갈래의 기준은 「코어에 닿았는가를 아는가」다. 닿지 않은 것이 확실한 것은 연결이 맺어지지 않은 경우
 * 하나뿐이고, 응답을 받지 못한 나머지는 전부 {@code UNKNOWN} 이다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("CoreCommandsClient — 코어의 답을 갈래로")
class CoreCommandsClientTest {

    private static final UUID AUDIT = UUID.fromString("0199a000-0000-7000-8000-00000000a0d1");
    private static final UUID WAVE = UUID.fromString("0199a000-0000-7000-8000-0000000000a1");
    private static final UUID ROUTE = UUID.fromString("0199a000-0000-7000-8000-0000000000b1");
    private static final UUID TARGET = UUID.fromString("0199a000-0000-7000-8000-0000000000b2");
    private static final UUID ORDER = UUID.fromString("0199a000-0000-7000-8000-0000000000c1");
    private static final String INTERNAL_TOKEN = "unit-test-only-internal-token-0123456789";

    private HttpServer core;
    private final List<Map<String, @Nullable String>> received = new CopyOnWriteArrayList<>();
    private final Map<String, Function<HttpExchange, Reply>> routes = new ConcurrentHashMap<>();

    @BeforeEach
    void startCore() throws IOException {
        core = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        core.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(Map.of(
                    "method", exchange.getRequestMethod(),
                    "path", exchange.getRequestURI().toString(),
                    "auditId", String.valueOf(exchange.getRequestHeaders().getFirst(MdcKeys.AUDIT_ID_HEADER)),
                    "internalToken", String.valueOf(exchange.getRequestHeaders().getFirst(InternalToken.HEADER)),
                    "body", body));
            Reply reply = routes.getOrDefault(exchange.getRequestURI().getPath(), e -> new Reply(404, "{}", 0))
                    .apply(exchange);
            sleep(reply.delayMillis());
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",
                    reply.status() >= 400 ? "application/problem+json" : "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        core.start();
    }

    @AfterEach
    void stopCore() {
        core.stop(0);
    }

    @Test
    void 성공은_Applied_이고_감사_id_가_상관_헤더로_간다() {
        routes.put("/api/v1/plans/" + WAVE + "/run", e -> new Reply(200,
                "{\"waveId\":\"" + WAVE + "\",\"outcome\":\"PLANNED\"}", 0));

        CoreReply reply = delegate(coreUrl(), new OpsCommand.RunPlan(WAVE, null, null, "FAST"));

        assertThat(reply).isEqualTo(new CoreReply.Applied(new CoreReply.PlanRun(WAVE, "PLANNED")));
        assertThat(received).singleElement().satisfies(request -> {
            assertThat(request.get("method")).isEqualTo("POST");
            assertThat(request.get("path")).isEqualTo("/api/v1/plans/" + WAVE + "/run?mode=FAST");
            assertThat(request.get("auditId")).isEqualTo(AUDIT.toString());
        });
    }

    @Test
    void 기존_위임_셋이_모두_내부_토큰을_싣는다() {
        // ADR-055 결정 4 — 빠지면 코어가 401 을 돌려주고 기존 커맨드가 머지 직후 깨진다. 취소는 코어에서 면제
        // 경로지만 싣는다: 호출마다 고르는 규칙은 새는 규칙이다.
        delegate(coreUrl(), new OpsCommand.RunPlan(WAVE, null, null, null));
        delegate(coreUrl(), new OpsCommand.ReassignStop(ROUTE, ORDER, TARGET));
        delegate(coreUrl(), new OpsCommand.CancelOrder(ORDER, null));

        assertThat(received).extracting(request -> request.get("path"))
                .as("전제 — 세 위임이 코어에 닿았다")
                .containsExactly("/api/v1/plans/" + WAVE + "/run",
                        "/api/v1/routes/" + ROUTE + "/stops/" + ORDER + "/reassign",
                        "/api/v1/orders/" + ORDER + "/cancel");
        assertThat(received).extracting(request -> request.get("internalToken")).containsOnly(INTERNAL_TOKEN);
    }

    @Test
    void 재배정은_계약의_본문으로_나가고_두_라우트의_revision_을_옮긴다() {
        routes.put("/api/v1/routes/" + ROUTE + "/stops/" + ORDER + "/reassign", e -> new Reply(200,
                "{\"orderId\":\"" + ORDER + "\",\"fromRouteId\":\"" + ROUTE + "\",\"fromRevision\":3,"
                        + "\"toRouteId\":\"" + TARGET + "\",\"toRevision\":5}", 0));

        CoreReply reply = delegate(coreUrl(), new OpsCommand.ReassignStop(ROUTE, ORDER, TARGET));

        assertThat(reply).isEqualTo(new CoreReply.Applied(new CoreReply.StopReassigned(ORDER, ROUTE, 3, TARGET, 5)));
        assertThat(received.getFirst().get("body")).isEqualTo("{\"targetRouteId\":\"" + TARGET + "\"}");
    }

    @Test
    void 취소의_답에서_주소는_옮기지_않는다() {
        routes.put("/api/v1/orders/" + ORDER + "/cancel", e -> new Reply(200,
                "{\"orderId\":\"" + ORDER + "\",\"status\":\"CANCELLED\",\"address\":{\"line\":\"서울시 어딘가 1\"}}", 0));

        CoreReply reply = delegate(coreUrl(), new OpsCommand.CancelOrder(ORDER, "고객 요청"));

        assertThat(reply).isEqualTo(new CoreReply.Applied(new CoreReply.OrderCancelled(ORDER, "CANCELLED")));
        assertThat(received.getFirst().get("body")).isEqualTo("{\"reason\":\"고객 요청\"}");
    }

    @Test
    void 거절은_코어의_상태와_본문을_바이트_그대로_싣는다() {
        String problem = "{\"type\":\"https://dawnline.internal/problems/conflict\",\"status\":409,\"code\":\"conflict\"}";
        routes.put("/api/v1/orders/" + ORDER + "/cancel", e -> new Reply(409, problem, 0));

        CoreReply reply = delegate(coreUrl(), new OpsCommand.CancelOrder(ORDER, null));

        assertThat(reply).isEqualTo(new CoreReply.Rejected(409, problem, "application/problem+json"));
    }

    @Test
    void 코어의_5xx_는_적용됐는지_모른다() {
        routes.put("/api/v1/plans/" + WAVE + "/run", e -> new Reply(500, "{\"code\":\"internal\"}", 0));

        CoreReply reply = delegate(coreUrl(), new OpsCommand.RunPlan(WAVE, null, null, null));

        assertThat(reply).isInstanceOfSatisfying(CoreReply.Unknown.class, unknown -> {
            assertThat(unknown.timedOut()).isFalse();
            assertThat(unknown.coreStatus()).isEqualTo(500);
        });
    }

    @Test
    void 응답_전에_시간이_다_되면_적용됐는지_모른다() {
        routes.put("/api/v1/plans/" + WAVE + "/run", e -> new Reply(200, "{}", 1_500));

        CoreReply reply = delegate(coreUrl(), new OpsCommand.RunPlan(WAVE, null, null, null));

        assertThat(received).as("요청은 코어에 닿았다 — 그래서 FAILED 가 아니다").hasSize(1);
        assertThat(reply).isInstanceOfSatisfying(CoreReply.Unknown.class,
                unknown -> assertThat(unknown.timedOut()).isTrue());
    }

    @Test
    void 연결이_맺어지지_않으면_닿지_않은_것이_확실하다() throws IOException {
        String closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = "http://127.0.0.1:" + socket.getLocalPort();
        }
        CoreReply reply = delegate(closed, new OpsCommand.RunPlan(WAVE, null, null, null));

        assertThat(reply).isInstanceOf(CoreReply.Unreachable.class);
    }

    @Test
    void 위임_범위_밖의_호출에는_감사_id_가_실리지_않는다() {
        routes.put("/api/v1/plans/" + WAVE + "/run", e -> new Reply(200,
                "{\"waveId\":\"" + WAVE + "\",\"outcome\":\"PLANNED\"}", 0));

        runner(coreUrl()).run(context -> context.getBean(
                com.dawnline.ops.adapter.out.core.dispatch.api.PlanControllerApi.class).run(WAVE, null, null, null));

        assertThat(received.getFirst().get("auditId")).isEqualTo("null");
    }

    private CoreReply delegate(String baseUrl, OpsCommand command) {
        CoreReply[] reply = new CoreReply[1];
        runner(baseUrl).run(context -> reply[0] = context.getBean(CoreCommands.class).delegate(AUDIT, command));
        return reply[0];
    }

    private static ApplicationContextRunner runner(String baseUrl) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class, HttpClientAutoConfiguration.class,
                        ImperativeHttpClientAutoConfiguration.class, HttpServiceClientPropertiesAutoConfiguration.class,
                        RestClientAutoConfiguration.class, HttpServiceClientAutoConfiguration.class))
                .withUserConfiguration(CoreClientsConfig.class)
                // ops-api 의 모양 — 검사는 끄고 값은 싣는다(ADR-055 결정 4).
                .withBean(InternalTokenProperties.class, () -> new InternalTokenProperties(false, INTERNAL_TOKEN))
                .withPropertyValues(
                        "spring.http.serviceclient.dispatch.base-url=" + baseUrl,
                        "spring.http.serviceclient.dispatch.connect-timeout=1s",
                        "spring.http.serviceclient.dispatch.read-timeout=500ms",
                        "spring.http.serviceclient.order.base-url=" + baseUrl,
                        "spring.http.serviceclient.order.read-timeout=500ms");
    }

    private String coreUrl() {
        return "http://127.0.0.1:" + core.getAddress().getPort();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Reply(int status, String body, long delayMillis) {
    }
}
