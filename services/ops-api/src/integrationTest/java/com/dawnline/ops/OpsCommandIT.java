package com.dawnline.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.observability.MdcKeys;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 운영자 커맨드 끝에서 끝까지 — 토큰 → 역할 → 감사 행 → 코어 위임 → 응답 (DESIGN.md §5.5 「커맨드 위임」).
 *
 * <p>실제 필터 체인(임의 포트)과 실제 PostgreSQL 을 쓰고, 코어는 JDK {@link HttpServer} 하나다. dispatch 그룹은
 * 그 가짜 코어로, order 그룹은 <strong>닫힌 포트</strong>로 보낸다 — 연결이 맺어지지 않는 갈래(FAILED)를 같은
 * 컨텍스트에서 보기 위해서다.
 *
 * <p>토큰은 운영의 검증기가 보는 다섯 클레임을 그대로 찍는다({@code make token} 과의 일치는
 * {@code OpsTokenScriptTest} 가 본다). 감사 행은 이 클래스의 actor({@value #ACTOR_PREFIX}*)로 만들고 지운다.
 */
@SpringBootTest(classes = OpsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("OpsCommandIT — 토큰 · 역할 · 감사 · 위임")
class OpsCommandIT extends OpsIntegrationTestBase {

    private static final String ACTOR_PREFIX = "it-ops-command-";
    private static final UUID WAVE = UUID.fromString("0199a000-0000-7000-8000-0000000000a1");
    private static final UUID ROUTE = UUID.fromString("0199a000-0000-7000-8000-0000000000b1");
    private static final UUID TARGET = UUID.fromString("0199a000-0000-7000-8000-0000000000b2");
    private static final UUID ORDER = UUID.fromString("0199a000-0000-7000-8000-0000000000c1");

    private static final HttpServer CORE = startCore();
    private static final List<Map<String, String>> RECEIVED = new CopyOnWriteArrayList<>();
    private static final String CLOSED_PORT_URL = closedPortUrl();

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("dawnline.ops.kpi.on-time-initial-delay-ms", () -> "3600000");
        registry.add("spring.http.serviceclient.dispatch.base-url", () -> "http://127.0.0.1:" + CORE.getAddress().getPort());
        registry.add("spring.http.serviceclient.order.base-url", () -> CLOSED_PORT_URL);
    }

    @BeforeEach
    void clearReceived() {
        RECEIVED.clear();
    }

    @AfterEach
    void deleteOwnRows() {
        jdbc.update("DELETE FROM audit_logs WHERE actor LIKE ?", ACTOR_PREFIX + "%");
    }

    @AfterAll
    static void stopCore() {
        CORE.stop(0);
    }

    @Test
    void 토큰이_없거나_서명이_다르거나_만료됐으면_401_이고_아무것도_남지_않는다() throws Exception {
        String wrongKey = token("OPS_OPERATOR", "a", "another-secret-0123456789abcdef-0123456789", Duration.ofHours(1));
        String expired = token("OPS_OPERATOR", "b", JWT_SECRET, Duration.ofMinutes(-5));

        assertThat(post("/api/v1/plans/" + WAVE + "/run", null, "").statusCode()).isEqualTo(401);
        assertThat(post("/api/v1/plans/" + WAVE + "/run", wrongKey, "").statusCode()).isEqualTo(401);
        assertThat(post("/api/v1/plans/" + WAVE + "/run", expired, "").statusCode()).isEqualTo(401);

        assertThat(ownRows()).isEmpty();
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    void 뷰어의_커맨드는_403_이고_감사_행도_코어_호출도_없다() throws Exception {
        HttpResponse<String> response = post("/api/v1/plans/" + WAVE + "/run", token("OPS_VIEWER", "viewer"), "");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(ownRows()).isEmpty();
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    void 뷰어는_조회를_통과한다_404_는_인가_뒤의_답이다() throws Exception {
        assertThat(get("/api/v1/no-such-view", token("OPS_VIEWER", "viewer")).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/no-such-view", null).statusCode()).isEqualTo(401);
    }

    @Test
    void 운영자의_재계획은_위임되고_감사_행이_SUCCEEDED_이며_코어가_받은_상관_헤더가_그_행이다() throws Exception {
        HttpResponse<String> response = post("/api/v1/plans/" + WAVE + "/run?mode=FAST", token("OPS_OPERATOR", "kim"), "");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("\"waveId\":\"" + WAVE + "\"", "\"outcome\":\"PLANNED\"");
        String auditId = response.headers().firstValue(MdcKeys.AUDIT_ID_HEADER).orElseThrow();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT actor, action, target_type, target_id, request::text AS request, result FROM audit_logs WHERE id = ?::uuid",
                auditId);
        assertThat(row).containsEntry("actor", ACTOR_PREFIX + "kim")
                .containsEntry("action", "RUN_PLAN")
                .containsEntry("target_type", "WAVE")
                .containsEntry("target_id", WAVE)
                .containsEntry("result", "SUCCEEDED");
        assertThat((String) row.get("request")).contains("\"mode\": \"FAST\"").doesNotContain("campId");
        assertThat(RECEIVED).singleElement().satisfies(request ->
                assertThat(request.get("auditId")).as("코어 로그가 이 id 로 감사 행을 가리킨다").isEqualTo(auditId));
    }

    @Test
    void 코어의_거절은_상태와_본문이_그대로_오고_감사_행은_REJECTED_다() throws Exception {
        HttpResponse<String> response = post("/api/v1/routes/" + ROUTE + "/stops/" + ORDER + "/reassign",
                token("ADMIN", "admin"), "{\"targetRouteId\":\"" + TARGET + "\"}");

        assertThat(response.statusCode()).as("ADMIN 은 OPS_OPERATOR 를 포함한다 — 인가를 지나 코어의 409 가 왔다")
                .isEqualTo(409);
        assertThat(response.body()).isEqualTo(REJECTION);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(resultOf(response)).isEqualTo("REJECTED");
        assertThat(RECEIVED.getFirst().get("body")).isEqualTo("{\"targetRouteId\":\"" + TARGET + "\"}");
    }

    @Test
    void 코어에_연결되지_않으면_502_이고_감사_행은_FAILED_다() throws Exception {
        HttpResponse<String> response = post("/api/v1/orders/" + ORDER + "/cancel", token("OPS_OPERATOR", "kim"),
                "{\"reason\":\"고객 요청\"}");

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("\"code\":\"core-unreachable\"",
                "\"auditId\":\"" + response.headers().firstValue(MdcKeys.AUDIT_ID_HEADER).orElseThrow() + "\"");
        assertThat(resultOf(response)).isEqualTo("FAILED");
    }

    @Test
    void 코어가_거절할_본문은_코어에_가기_전에_400_이고_감사_행이_없다() throws Exception {
        HttpResponse<String> response = post("/api/v1/routes/" + ROUTE + "/stops/" + ORDER + "/reassign",
                token("OPS_OPERATOR", "kim"), "{}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(ownRows()).isEmpty();
        assertThat(RECEIVED).isEmpty();
    }

    // --- 가짜 코어 ----------------------------------------------------------------------------

    private static final String REJECTION =
            "{\"type\":\"https://dawnline.internal/problems/hard-rule-violated\",\"status\":409,\"code\":\"hard-rule-violated\"}";

    private static HttpServer startCore() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                RECEIVED.add(Map.of("path", path,
                        "auditId", String.valueOf(exchange.getRequestHeaders().getFirst(MdcKeys.AUDIT_ID_HEADER)),
                        "body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                boolean run = path.endsWith("/run");
                String body = run ? "{\"waveId\":\"" + WAVE + "\",\"outcome\":\"PLANNED\"}" : REJECTION;
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", run ? "application/json" : "application/problem+json");
                exchange.sendResponseHeaders(run ? 200 : 409, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String closedPortUrl() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- 도우미 -------------------------------------------------------------------------------

    private String resultOf(HttpResponse<String> response) {
        String auditId = response.headers().firstValue(MdcKeys.AUDIT_ID_HEADER).orElseThrow();
        return jdbc.queryForObject("SELECT result FROM audit_logs WHERE id = ?::uuid", String.class, auditId);
    }

    private List<Map<String, Object>> ownRows() {
        return jdbc.queryForList("SELECT id FROM audit_logs WHERE actor LIKE ?", ACTOR_PREFIX + "%");
    }

    private HttpResponse<String> post(String path, @Nullable String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, @Nullable String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(String role, String actor) throws Exception {
        return token(role, actor, JWT_SECRET, Duration.ofHours(1));
    }

    /** {@code make token} 과 같은 다섯 클레임. */
    private static String token(String role, String actor, String secret, Duration ttl) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("dawnline-ops-token")
                .subject(ACTOR_PREFIX + actor)
                .claim("roles", List.of(role))
                .issueTime(Date.from(now.minusSeconds(60)))
                .expirationTime(Date.from(now.plus(ttl)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
