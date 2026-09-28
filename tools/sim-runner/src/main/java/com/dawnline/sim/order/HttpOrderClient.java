package com.dawnline.sim.order;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link OrderClient} 의 HTTP 구현. JDK {@code java.net.http} 를 쓴다.
 *
 * <p>{@code RestClient} 를 쓰지 않는 이유는 {@code build.gradle.kts} 에 적었다 — 서버를 띄우지
 * 않는 도구에 서블릿 컨테이너를 끌고 올 이유가 없다.
 *
 * <p>어떤 실패도 예외로 올리지 않는다. 200건 중 3건이 실패한 것과 실행이 3번째에서 죽은 것은
 * 전혀 다른 사건이고, 이 도구가 알려 줘야 하는 것은 전자다.
 */
public final class HttpOrderClient implements OrderClient, OrderCancelClient {

    private final HttpClient http;
    private final ObjectMapper json;
    private final URI endpoint;
    private final Duration requestTimeout;

    /**
     * @param http           HTTP 클라이언트
     * @param json           JSON 매퍼 (Jackson 3)
     * @param baseUrl        order-service 주소
     * @param requestTimeout 요청 하나의 타임아웃
     */
    public HttpOrderClient(HttpClient http, ObjectMapper json, String baseUrl, Duration requestTimeout) {
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
        this.endpoint = URI.create(Objects.requireNonNull(baseUrl, "baseUrl").replaceAll("/+$", "")
                + "/api/v1/orders");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    @Override
    public Response place(GeneratedOrder order, String idempotencyKey) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(order)))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 201) {
                UUID orderId = orderId(response.body());
                if (orderId != null) {
                    return Response.accepted(orderId);
                }
            }
            return Response.of(response.statusCode(), problemCode(response.body()));
        } catch (IOException exception) {
            return Response.transportFailure(exception.getClass().getSimpleName());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Response.transportFailure("interrupted");
        }
    }

    @Override
    public Response cancel(UUID orderId) {
        // 고객이 부르는 경로 그대로다(§5.1) — 사유는 도구가 지어내는 고정 문자열이다(개인정보가 섞일 자리를 만들지 않는다, §9.3).
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/" + orderId + "/cancel"))
                .header("Content-Type", "application/json")
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"시뮬레이션 취소\"}"))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return Response.of(response.statusCode(), problemCode(response.body()));
        } catch (IOException exception) {
            return Response.transportFailure(exception.getClass().getSimpleName());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Response.transportFailure("interrupted");
        }
    }

    /** 접수 본문의 {@code orderId}. 읽지 못하면 {@code null} — 그 주문은 취소 대상에서 빠진다(리포트가 센다). */
    private @Nullable UUID orderId(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = json.readTree(body).path("orderId");
            return node.isString() ? UUID.fromString(node.stringValue()) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** 오류 본문에서 {@code code} 를 꺼낸다. 본문이 JSON 이 아니어도 실행을 멈추지 않는다. */
    private @Nullable String problemCode(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = json.readTree(body).path("code");
            return node.isString() ? node.stringValue() : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
