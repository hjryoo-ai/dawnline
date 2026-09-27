package com.dawnline.sim.fleet;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link OpsClient} 의 HTTP 구현 — JDK {@code java.net.http}, 운영자의 JWT 를 {@code Authorization: Bearer} 로 싣는다.
 *
 * <p>{@code HttpOrderClient} 와 같은 이유로 {@code RestClient} 를 쓰지 않는다(서버를 띄우지 않는 도구에 서블릿 컨테이너를 끌고 올
 * 이유가 없다). 조회의 비 2xx 와 전송 실패는 {@link OpsException} 이고, 커맨드는 상태 · 감사 id · 코드를 {@link Reply} 로 돌려준다.
 */
public final class HttpOpsClient implements OpsClient {

    /** 감사 행 id 헤더 — ops-api 의 {@code MdcKeys.AUDIT_ID_HEADER}. */
    static final String AUDIT_ID_HEADER = "X-Dawnline-Audit-Id";

    private final HttpClient http;
    private final ObjectMapper json;
    private final String baseUrl;
    private final String token;
    private final Duration requestTimeout;

    /**
     * @param http           HTTP 클라이언트
     * @param json           JSON 매퍼 — 모르는 칸은 무시한다(ops-api 가 칸을 더해도 도구가 깨지지 않게)
     * @param baseUrl        ops-api 주소
     * @param token          운영자 JWT
     * @param requestTimeout 요청 하나의 타임아웃
     */
    public HttpOpsClient(HttpClient http, ObjectMapper json, String baseUrl, String token, Duration requestTimeout) {
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl").replaceAll("/+$", "") + "/api/v1";
        this.token = Objects.requireNonNull(token, "token");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    @Override
    public List<UUID> camps() {
        List<UUID> camps = new ArrayList<>();
        for (JsonNode camp : read("캠프 목록", "/camps").path("camps")) {
            camps.add(UUID.fromString(camp.path("campId").asString()));
        }
        return List.copyOf(camps);
    }

    @Override
    public List<Wave> waves(UUID campId, Instant from, Instant to) {
        JsonNode body = read("웨이브 목록", "/camps/" + campId + "/waves?from=" + query(from.toString())
                + "&to=" + query(to.toString()));
        List<Wave> waves = new ArrayList<>();
        for (JsonNode wave : body.path("waves")) {
            waves.add(json.treeToValue(wave, Wave.class));
        }
        return List.copyOf(waves);
    }

    @Override
    public Assessment fleetFeasibility(UUID waveId) {
        return json.treeToValue(read("함대 판정", "/waves/" + waveId + "/fleet-feasibility"), Assessment.class);
    }

    @Override
    public List<Vehicle> vehicles(UUID campId) {
        List<Vehicle> vehicles = new ArrayList<>();
        for (JsonNode vehicle : read("차량 목록", "/vehicles?campId=" + campId).path("vehicles")) {
            vehicles.add(json.treeToValue(vehicle, Vehicle.class));
        }
        return List.copyOf(vehicles);
    }

    @Override
    public Reply addVehicle(NewVehicle vehicle) {
        return command("/vehicles", json.writeValueAsString(vehicle));
    }

    @Override
    public Reply deactivate(UUID vehicleId) {
        return command("/vehicles/" + vehicleId + "/deactivate", "");
    }

    private JsonNode read(String what, String path) {
        HttpResponse<String> response = send(what, request(path).GET().build());
        if (response.statusCode() != 200) {
            throw new OpsException(what, response.statusCode(), problemCode(response.body()));
        }
        return json.readTree(response.body());
    }

    private Reply command(String path, String body) {
        HttpResponse<String> response = send(path, request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
        String auditId = response.headers().firstValue(AUDIT_ID_HEADER).orElse(null);
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            JsonNode id = json.readTree(response.body()).path("id");
            return new Reply(response.statusCode(), auditId, null,
                    id.isString() ? UUID.fromString(id.asString()) : null);
        }
        return new Reply(response.statusCode(), auditId, problemCode(response.body()), null);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .timeout(requestTimeout);
    }

    private HttpResponse<String> send(String what, HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new OpsException(what + " (" + e.getClass().getSimpleName() + ")", 0, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpsException(what + " (interrupted)", 0, null);
        }
    }

    private @Nullable String problemCode(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode code = json.readTree(body).path("code");
            return code.isString() ? code.stringValue() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String query(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
