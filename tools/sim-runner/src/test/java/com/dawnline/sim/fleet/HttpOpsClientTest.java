package com.dawnline.sim.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.messaging.json.EventJson;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * ops-api 클라이언트 — 실제 HTTP 로 (ADR-067). 본문의 모양은 ops-api 의 것({@code contracts/openapi/ops-api.yaml})이다:
 * 목록은 이름 있는 칸에 담겨 오고, 커맨드는 감사 id 헤더를 모든 결과에 싣는다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class HttpOpsClientTest {

    private static final UUID CAMP = UUID.fromString("01a06edd-6c00-7000-8001-000000000001");
    private static final UUID WAVE = UUID.fromString("0199a000-0000-7000-8000-0000000000a1");
    private static final UUID VEHICLE = UUID.fromString("0199a000-0000-7000-8000-0000000000d2");

    private HttpServer server;
    private final List<Map<String, String>> received = new CopyOnWriteArrayList<>();
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();

    private record Answer(int status, String body, String auditId) {
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.add(Map.of("method", exchange.getRequestMethod(),
                    "uri", exchange.getRequestURI().toString(),
                    "authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                    "body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            Answer answer = answers.getOrDefault(exchange.getRequestURI().getPath(), new Answer(404, "{}", ""));
            if (!answer.auditId().isEmpty()) {
                exchange.getResponseHeaders().set(HttpOpsClient.AUDIT_ID_HEADER, answer.auditId());
            }
            byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answer.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpOpsClient client(String baseUrl) {
        return new HttpOpsClient(HttpClient.newHttpClient(), EventJson.standardMapper(), baseUrl, "jwt-operator",
                Duration.ofSeconds(5));
    }

    private HttpOpsClient client() {
        return client("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @Test
    void 조회는_운영자_토큰을_싣고_이름_있는_칸에서_읽는다() {
        answers.put("/api/v1/camps", new Answer(200, "{\"camps\":[{\"campId\":\"" + CAMP + "\",\"campCode\":\"C1\","
                + "\"waves\":3}]}", ""));
        answers.put("/api/v1/camps/" + CAMP + "/waves", new Answer(200, "{\"campId\":\"" + CAMP + "\",\"waves\":[{"
                + "\"waveId\":\"" + WAVE + "\",\"serviceTier\":\"DAWN\",\"cutoffAt\":\"2026-09-27T15:00:00Z\","
                + "\"status\":\"PLANNED\",\"orderCount\":4500,\"planId\":null,\"unassignedCount\":9,\"routeCount\":12,"
                + "\"closedAt\":\"2026-09-27T15:01:30Z\"}]}", ""));

        HttpOpsClient ops = client();

        assertThat(ops.camps()).containsExactly(CAMP);
        assertThat(ops.waves(CAMP, Instant.parse("2026-09-27T14:00:00Z"), Instant.parse("2026-09-27T16:00:00Z")))
                .containsExactly(new OpsClient.Wave(WAVE, "DAWN", Instant.parse("2026-09-27T15:00:00Z"), "PLANNED",
                        4500, 9, 12, Instant.parse("2026-09-27T15:01:30Z")));
        assertThat(received).allSatisfy(request ->
                assertThat(request.get("authorization")).isEqualTo("Bearer jwt-operator"));
        assertThat(received.get(1).get("uri")).contains("from=2026-09-27T14%3A00%3A00Z");
    }

    @Test
    void 함대_판정의_템플릿이_없는_줄은_null_이다() {
        answers.put("/api/v1/waves/" + WAVE + "/fleet-feasibility", new Answer(200, "{\"waveId\":\"" + WAVE + "\","
                + "\"campId\":\"" + CAMP + "\",\"assessedAt\":\"2026-09-27T14:58:00Z\",\"candidates\":11,\"stops\":9,"
                + "\"fleet\":8,\"maxStopsPerRoute\":120,\"headroomPercent\":80,\"feasible\":false,\"combinations\":["
                + "{\"cold\":true,\"hazmat\":true,\"large\":false,\"label\":\"냉장∧위험물\",\"status\":\"NO_TEMPLATE\","
                + "\"demandStops\":1,\"vehicles\":0,\"shortfall\":null,\"template\":null}]}", ""));

        OpsClient.Assessment assessment = client().fleetFeasibility(WAVE);

        assertThat(assessment.candidates()).isEqualTo(11);
        assertThat(assessment.combinations()).singleElement().satisfies(line -> {
            assertThat(line.noTemplate()).isTrue();
            assertThat(line.shortfall()).isNull();
            assertThat(line.toAdd()).isZero();
        });
    }

    @Test
    void 조회가_거절되면_상태와_코드를_싣고_던진다() {
        answers.put("/api/v1/waves/" + WAVE + "/fleet-feasibility",
                new Answer(409, "{\"code\":\"wave-already-planned\",\"status\":409}", ""));

        assertThatThrownBy(() -> client().fleetFeasibility(WAVE))
                .isInstanceOfSatisfying(OpsClient.OpsException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.code()).isEqualTo("wave-already-planned");
                });
    }

    @Test
    void ops_api_에_닿지_못하면_상태_0_으로_던진다() throws IOException {
        String closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = "http://127.0.0.1:" + socket.getLocalPort();
        }
        String base = closed;

        assertThatThrownBy(() -> client(base).camps())
                .isInstanceOfSatisfying(OpsClient.OpsException.class, e -> assertThat(e.status()).isZero());
    }

    @Test
    void 커맨드는_감사_id_를_돌려주고_거절도_예외가_아니다() {
        answers.put("/api/v1/vehicles", new Answer(200, "{\"id\":\"" + VEHICLE + "\"}", "audit-1"));
        answers.put("/api/v1/vehicles/" + VEHICLE + "/deactivate",
                new Answer(409, "{\"code\":\"vehicle-in-service\",\"unfinishedStops\":3}", "audit-2"));
        HttpOpsClient ops = client();

        OpsClient.Reply added = ops.addVehicle(new OpsClient.NewVehicle(CAMP, "PSA1B2C3-0001", "VAN", 400_000,
                1_200_000, false, false, 45_000, 600, 250, LocalTime.of(23, 0), LocalTime.of(8, 0), "peak-sim"));
        OpsClient.Reply refused = ops.deactivate(VEHICLE);

        assertThat(added).isEqualTo(new OpsClient.Reply(200, "audit-1", null, VEHICLE));
        assertThat(refused).isEqualTo(new OpsClient.Reply(409, "audit-2", "vehicle-in-service", null));
        assertThat(received.getFirst().get("body")).contains("\"source\":\"peak-sim\"", "\"shiftStart\":\"23:00:00\"");
    }

    @Test
    void 조기_마감과_재배정은_계약의_본문을_싣고_라우트는_이름_있는_칸에서_읽는다() {
        UUID route = UUID.fromString("0199a000-0000-7000-8000-0000000000b2");
        UUID target = UUID.fromString("0199a000-0000-7000-8000-0000000000b1");
        UUID order = UUID.fromString("0199a000-0000-7000-8000-0000000000c3");
        answers.put("/api/v1/waves/" + WAVE + "/close", new Answer(200, "{\"waveId\":\"" + WAVE + "\",\"status\":\"CLOSED\","
                + "\"closeCause\":\"MANUAL\",\"closedAt\":\"2026-09-27T14:58:05Z\"}", "audit-3"));
        answers.put("/api/v1/waves/" + WAVE + "/routes", new Answer(200, "{\"waveId\":\"" + WAVE + "\",\"planId\":null,"
                + "\"depot\":null,\"routes\":[{\"routeId\":\"" + route + "\",\"vehicleId\":null,\"stopCount\":4,"
                + "\"atRisk\":false}]}", ""));
        answers.put("/api/v1/routes/" + route, new Answer(200, "{\"routeId\":\"" + route + "\",\"stops\":[{\"seq\":1,"
                + "\"lat\":37.5,\"lng\":127.0,\"plannedArrival\":\"2026-09-27T16:00:00Z\",\"status\":\"PLANNED\","
                + "\"orderIds\":[\"" + order + "\"]}]}", ""));
        answers.put("/api/v1/routes/" + route + "/stops/" + order + "/reassign",
                new Answer(409, "{\"code\":\"stop-not-planned\",\"stopStatus\":\"COMPLETED\"}", "audit-4"));
        HttpOpsClient ops = client();

        OpsClient.Reply closed = ops.closeWave(WAVE, "성수기 증차 완료");
        List<OpsClient.RouteSummary> routes = ops.waveRoutes(WAVE);
        List<OpsClient.RouteStop> stops = ops.routeStops(route);
        OpsClient.Reply reassigned = ops.reassign(route, order, target);

        assertThat(closed).isEqualTo(new OpsClient.Reply(200, "audit-3", null, null));
        assertThat(routes).containsExactly(new OpsClient.RouteSummary(route, 4));
        assertThat(stops).containsExactly(new OpsClient.RouteStop(1, "PLANNED", List.of(order)));
        assertThat(reassigned).isEqualTo(new OpsClient.Reply(409, "audit-4", "stop-not-planned", null));
        assertThat(received.getFirst().get("body")).isEqualTo("{\"reason\":\"성수기 증차 완료\"}");
        assertThat(received.getLast().get("body")).isEqualTo("{\"targetRouteId\":\"" + target + "\"}");
        assertThat(received).extracting(request -> request.get("method")).containsExactly("POST", "GET", "GET", "POST");
    }
}
