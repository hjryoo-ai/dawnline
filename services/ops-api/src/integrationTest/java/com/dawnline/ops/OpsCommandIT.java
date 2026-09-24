package com.dawnline.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.outbox.OutboxRepository;
import com.dawnline.messaging.web.OutboxAdminController;
import com.dawnline.observability.MdcKeys;
import com.dawnline.web.internal.InternalToken;
import com.dawnline.web.internal.InternalTokenProperties;
import com.dawnline.web.internal.InternalTokens;
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
import org.springframework.context.ApplicationContext;
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
    private static final UUID CLOSED_WAVE = UUID.fromString("0199a000-0000-7000-8000-0000000000a2");
    private static final UUID EVENT = UUID.fromString("0199a000-0000-7000-8000-0000000000e1");

    /** 가짜 코어가 요청을 받는 순간 감사 행을 읽는다 — 「위임 전에 PENDING 이 커밋됐다」의 관측 지점. */
    private static volatile @Nullable JdbcTemplate coreSideDb;

    private static final HttpServer CORE = startCore();
    private static final List<Map<String, String>> RECEIVED = new CopyOnWriteArrayList<>();
    private static final String CLOSED_PORT_URL = closedPortUrl();

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("dawnline.ops.kpi.on-time-initial-delay-ms", () -> "3600000");
        registry.add("spring.http.serviceclient.dispatch.base-url", () -> "http://127.0.0.1:" + CORE.getAddress().getPort());
        registry.add("spring.http.serviceclient.order.base-url", () -> CLOSED_PORT_URL);
        // 같은 가짜 코어를 접두어로 나눈다 — outbox 경로는 코어 넷이 같아서 어느 그룹으로 갔는지를 접두어가 말한다.
        registry.add("spring.http.serviceclient.fulfillment.base-url",
                () -> "http://127.0.0.1:" + CORE.getAddress().getPort() + "/fulfillment");
        registry.add("spring.http.serviceclient.tracking.base-url",
                () -> "http://127.0.0.1:" + CORE.getAddress().getPort() + "/tracking");
    }

    @BeforeEach
    void clearReceived() {
        RECEIVED.clear();
        coreSideDb = jdbc;
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
    void 감사_없는_outbox_재큐는_ops_api_에_없다() throws Exception {
        // libs/messaging 의 격리 조회·재큐는 조건(outbox + 서블릿 웹 앱)으로 켜지고 ops-api 는 그 조건을 만족한다.
        // ops-api 의 운영 표면은 전부 감사 행을 남기는데 그 경로는 남기지 않으므로 application.yml 의
        // dawnline.messaging.outbox.admin-api=false 로 끈다 (ADR-015 후속 정정 결정 4). 이 테스트는 그 제외가
        // 「검토했는데 뺀 것」임을 말한다 — 그 줄이 사라지면 여기가 빨개진다.
        assertThat(context.getBeanNamesForType(OutboxRepository.class))
                .as("전제 — 자동 설정의 조건은 맞는다(ops-api 에도 outbox 가 있다). 이것이 비면 아래의 부재는 "
                        + "아무것도 말하지 않는다")
                .isNotEmpty();
        assertThat(context.getBeanNamesForType(OutboxAdminController.class)).isEmpty();

        HttpResponse<String> response = post("/api/v1/admin/outbox/" + TARGET + "/requeue",
                token("OPS_OPERATOR", "kim"), "");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(ownRows()).isEmpty();
    }

    @Test
    void 내부_토큰_검사는_ops_api_에_없다() {
        // libs/web 의 내부 토큰 검사는 서블릿 웹 앱 전부에 붙고 ops-api 도 그 조건을 만족한다. ops-api 의 쓰기는 JWT·역할·
        // 감사가 지키므로 application.yml 의 dawnline.web.internal-token.enforce=false 로 끈다 (ADR-055 결정 4) — 켜 두면
        // 운영자가 JWT 에 더해 내부 토큰까지 알아야 한다. 이 테스트는 그 제외가 「검토했는데 뺀 것」임을 말한다.
        assertThat(context.getBeanNamesForType(InternalTokenProperties.class))
                .as("전제 — libs/web 이 붙어 있고 토큰 값이 바인딩됐다(ops-api 는 그 값을 코어 호출에 싣는다). 이것이 "
                        + "비면 아래의 부재는 아무것도 말하지 않는다")
                .isNotEmpty();
        assertThat(context.getBean(InternalTokenProperties.class).enforce()).isFalse();
        assertThat(context.containsBean("dawnlineInternalTokenEnforcement")).isFalse();
    }

    @Test
    void 위임은_코어에_내부_토큰을_싣는다() throws Exception {
        // 가짜 코어는 진짜 코어처럼 토큰 없는 쓰기를 401 로 돌려준다(startCore). 재계획은 200, 재배정은 코어의 409 가
        // 와야 한다 — 401 이면 헤더가 빠진 것이다. 취소(order 그룹)는 이 IT 에서 닫힌 포트로 가므로
        // CoreCommandsClientTest 가 본다.
        assertThat(post("/api/v1/plans/" + WAVE + "/run", token("OPS_OPERATOR", "kim"), "").statusCode())
                .isEqualTo(200);
        assertThat(post("/api/v1/routes/" + ROUTE + "/stops/" + ORDER + "/reassign", token("OPS_OPERATOR", "kim"),
                "{\"targetRouteId\":\"" + TARGET + "\"}").statusCode()).isEqualTo(409);

        assertThat(RECEIVED).as("전제 — 두 위임이 코어에 닿았다").hasSize(2);
        assertThat(RECEIVED).extracting(request -> request.get("internalToken"))
                .containsOnly(InternalTokens.TEST_TOKEN);
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

    // --- 작업 2: 조기 마감 · outbox 재큐 · 격리 목록 --------------------------------------------

    @Test
    void 조기_마감은_fulfillment_로_위임되고_감사_행은_CLOSE_WAVE_이며_코어가_받을_때_이미_PENDING_이었다() throws Exception {
        HttpResponse<String> response = post("/api/v1/waves/" + WAVE + "/close", token("OPS_OPERATOR", "kim"),
                "{\"reason\":\"피크 대비 선마감\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("\"closeCause\":\"MANUAL\"");
        Map<String, Object> row = rowOf(response);
        assertThat(row).containsEntry("action", "CLOSE_WAVE").containsEntry("target_type", "WAVE")
                .containsEntry("target_id", WAVE).containsEntry("result", "SUCCEEDED");
        assertThat((String) row.get("request")).contains("\"reason\": \"피크 대비 선마감\"");
        assertThat(RECEIVED).singleElement().satisfies(request -> {
            assertThat(request.get("path")).isEqualTo("/fulfillment/api/v1/waves/" + WAVE + "/close");
            assertThat(request.get("resultAtReceipt")).as("감사 행은 위임 전에 커밋된다(§5.5)").isEqualTo("PENDING");
        });
    }

    @Test
    void 이미_닫힌_웨이브는_코어의_409_가_마감_원인과_함께_그대로_오고_REJECTED_다() throws Exception {
        HttpResponse<String> response = post("/api/v1/waves/" + CLOSED_WAVE + "/close", token("OPS_OPERATOR", "kim"),
                "{\"reason\":\"다시 누름\"}");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).as("RB-07 — 다시 누른 사람은 closeCause 로 앞의 요청이 적용됐는지 읽는다")
                .isEqualTo(WAVE_NOT_OPEN);
        assertThat(resultOf(response)).isEqualTo("REJECTED");
    }

    @Test
    void 조기_마감의_이유가_없거나_공백이면_코어에_가기_전에_400_이고_감사_행이_없다() throws Exception {
        for (String body : List.of("{}", "{\"reason\":\"  \"}", "{\"reason\":\"" + "가".repeat(201) + "\"}")) {
            assertThat(post("/api/v1/waves/" + WAVE + "/close", token("OPS_OPERATOR", "kim"), body).statusCode())
                    .as(body).isEqualTo(400);
        }
        assertThat(ownRows()).isEmpty();
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    void 재큐는_service_가_말한_코어로_위임되고_감사_행은_REQUEUE_OUTBOX_다() throws Exception {
        HttpResponse<String> response = post("/api/v1/admin/outbox/tracking/" + EVENT + "/requeue",
                token("OPS_OPERATOR", "kim"), "");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("\"id\":\"" + EVENT + "\"", "\"eventType\":\"route.progress\"");
        Map<String, Object> row = rowOf(response);
        assertThat(row).containsEntry("action", "REQUEUE_OUTBOX").containsEntry("target_type", "OUTBOX_EVENT")
                .containsEntry("target_id", EVENT).containsEntry("result", "SUCCEEDED");
        assertThat((String) row.get("request")).contains("\"service\": \"tracking\"");
        assertThat(RECEIVED).singleElement().satisfies(request -> {
            assertThat(request.get("path")).isEqualTo("/tracking/api/v1/admin/outbox/" + EVENT + "/requeue");
            assertThat(request.get("resultAtReceipt")).isEqualTo("PENDING");
        });
    }

    @Test
    void 모르는_service_는_404_이고_감사_행도_코어_호출도_없다() throws Exception {
        String operator = token("OPS_OPERATOR", "kim");

        assertThat(post("/api/v1/admin/outbox/ops-api/" + EVENT + "/requeue", operator, "").statusCode())
                .as("ops-api 의 outbox 관리 경로는 꺼져 있다 — 넷에 들지 않는다").isEqualTo(404);
        assertThat(post("/api/v1/admin/outbox/Tracking/" + EVENT + "/requeue", operator, "").statusCode())
                .isEqualTo(404);
        HttpResponse<String> list = get("/api/v1/admin/outbox/nowhere/quarantined", operator);
        assertThat(list.statusCode()).isEqualTo(404);
        assertThat(list.body()).contains("\"code\":\"not-found\"", "\"service\":\"nowhere\"");
        assertThat(ownRows()).isEmpty();
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    void 뷰어는_목록을_읽고_감사_행이_없으며_마감과_재큐는_403_이다() throws Exception {
        String viewer = token("OPS_VIEWER", "viewer");

        HttpResponse<String> list = get("/api/v1/admin/outbox/tracking/quarantined?limit=1", viewer);
        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        assertThat(list.body()).contains("\"total\":2", "\"publishAttempts\":1");
        assertThat(list.headers().firstValue(MdcKeys.AUDIT_ID_HEADER)).as("조회는 감사하지 않는다").isEmpty();
        assertThat(RECEIVED).singleElement().satisfies(request -> {
            assertThat(request.get("path")).isEqualTo("/tracking/api/v1/admin/outbox/quarantined");
            assertThat(request.get("query")).isEqualTo("limit=1");
        });

        assertThat(post("/api/v1/waves/" + WAVE + "/close", viewer, "{\"reason\":\"x\"}").statusCode()).isEqualTo(403);
        assertThat(post("/api/v1/admin/outbox/tracking/" + EVENT + "/requeue", viewer, "").statusCode()).isEqualTo(403);
        assertThat(ownRows()).isEmpty();
        assertThat(RECEIVED).as("403 은 코어에 가지 않는다").hasSize(1);
    }

    // --- 가짜 코어 ----------------------------------------------------------------------------

    private static final String REJECTION =
            "{\"type\":\"https://dawnline.internal/problems/hard-rule-violated\",\"status\":409,\"code\":\"hard-rule-violated\"}";

    private static final String WAVE_NOT_OPEN = "{\"type\":\"https://dawnline.internal/problems/wave-not-open\","
            + "\"status\":409,\"code\":\"wave-not-open\",\"currentState\":\"CLOSED\",\"closeCause\":\"SCHEDULED\"}";

    private static final String UNAUTHORIZED =
            "{\"type\":\"https://dawnline.internal/problems/internal-token-required\",\"status\":401,"
                    + "\"code\":\"internal-token-required\"}";

    private static HttpServer startCore() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String internalToken = String.valueOf(exchange.getRequestHeaders().getFirst(InternalToken.HEADER));
                String auditId = String.valueOf(exchange.getRequestHeaders().getFirst(MdcKeys.AUDIT_ID_HEADER));
                RECEIVED.add(Map.of("path", path,
                        "query", String.valueOf(exchange.getRequestURI().getQuery()),
                        "auditId", auditId,
                        "resultAtReceipt", resultAtReceipt(auditId),
                        "internalToken", internalToken,
                        "body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                // 진짜 코어처럼 토큰 없는 운영자 쓰기는 401 이다(ADR-055) — 위임이 헤더를 잃으면 여기서 드러난다.
                // 조회(GET)는 토큰 대상이 아니다.
                boolean authorized = "GET".equals(exchange.getRequestMethod())
                        || InternalTokens.TEST_TOKEN.equals(internalToken);
                int status;
                String body;
                if (!authorized) {
                    status = 401;
                    body = UNAUTHORIZED;
                } else if (path.endsWith("/run")) {
                    status = 200;
                    body = "{\"waveId\":\"" + WAVE + "\",\"outcome\":\"PLANNED\"}";
                } else if (path.equals("/fulfillment/api/v1/waves/" + WAVE + "/close")) {
                    status = 200;
                    body = "{\"waveId\":\"" + WAVE + "\",\"status\":\"CLOSED\",\"closeCause\":\"MANUAL\","
                            + "\"closedAt\":\"2026-09-24T01:02:03Z\"}";
                } else if (path.equals("/fulfillment/api/v1/waves/" + CLOSED_WAVE + "/close")) {
                    status = 409;
                    body = WAVE_NOT_OPEN;
                } else if (path.equals("/tracking/api/v1/admin/outbox/" + EVENT + "/requeue")) {
                    status = 200;
                    body = "{\"id\":\"" + EVENT + "\",\"aggregateType\":\"Route\",\"aggregateId\":\"" + ROUTE
                            + "\",\"eventType\":\"route.progress\",\"topic\":\"dawnline.delivery.route-progress.v1\"}";
                } else if (path.equals("/tracking/api/v1/admin/outbox/quarantined")) {
                    status = 200;
                    body = "{\"total\":2,\"events\":[{\"id\":\"" + EVENT + "\",\"aggregateType\":\"Route\","
                            + "\"aggregateId\":\"" + ROUTE + "\",\"eventType\":\"route.progress\","
                            + "\"topic\":\"dawnline.delivery.route-progress.v1\",\"createdAt\":\"2026-09-24T00:00:00Z\","
                            + "\"failedAt\":\"2026-09-24T00:10:00Z\",\"publishAttempts\":1}]}";
                } else {
                    status = 409;
                    body = REJECTION;
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", status == 200 ? "application/json" : "application/problem+json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 요청이 코어에 닿은 순간의 감사 행 결과. 감사 id 가 없으면(조회) {@code "-"}. */
    private static String resultAtReceipt(String auditId) {
        JdbcTemplate db = coreSideDb;
        if (db == null || "null".equals(auditId)) {
            return "-";
        }
        List<String> results = db.queryForList("SELECT result FROM audit_logs WHERE id = ?::uuid", String.class, auditId);
        return results.isEmpty() ? "(없음)" : results.getFirst();
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

    private Map<String, Object> rowOf(HttpResponse<String> response) {
        String auditId = response.headers().firstValue(MdcKeys.AUDIT_ID_HEADER).orElseThrow();
        return jdbc.queryForMap("SELECT action, target_type, target_id, request::text AS request, result "
                + "FROM audit_logs WHERE id = ?::uuid", auditId);
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
