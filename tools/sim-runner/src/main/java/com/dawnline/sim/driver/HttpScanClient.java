package com.dawnline.sim.driver;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link ScanClient} 의 HTTP 구현. JDK {@code java.net.http} 를 쓴다 —
 * 이유는 {@code HttpOrderClient} 와 같다.
 *
 * <p>멱등 키 헤더를 보내지 않는다. 스캔 API 는 그것을 요구하지 않는다 — §8.5 의 멱등 키는
 * 「{@code (orderIds, type)} + 상태 머신」이고, 같은 스캔이 다시 가면 {@code STALE} 로
 * 흡수된다 ({@code ScanController} javadoc).
 */
public final class HttpScanClient implements ScanClient {

    /** ADR-009: 경로의 버전은 리터럴이다 — 서버가 {@code {version}} 으로 매핑할 뿐이다. */
    private static final String PATH = "/api/v1/routes/%s/stops/%d/events";

    private final HttpClient http;
    private final ObjectMapper json;
    private final String baseUrl;
    private final Duration requestTimeout;

    /**
     * @param http           HTTP 클라이언트
     * @param json           JSON 매퍼 (Jackson 3)
     * @param baseUrl        tracking-service 주소
     * @param requestTimeout 요청 하나의 타임아웃
     */
    public HttpScanClient(HttpClient http, ObjectMapper json, String baseUrl, Duration requestTimeout) {
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl").replaceAll("/+$", "");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    @Override
    public Response report(UUID routeId, ScanCall call) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + PATH.formatted(routeId, call.stopSeq())))
                .header("Content-Type", "application/json")
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(bodyOf(call))))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            return Response.of(response.statusCode(), problemCode(body), outcomes(body));
        } catch (IOException exception) {
            return Response.transportFailure(exception.getClass().getSimpleName());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Response.transportFailure("interrupted");
        }
    }

    /**
     * 요청 본문 — {@code ScanController.ScanRequest} 의 필드와 같아야 한다.
     *
     * <p>{@code stopSeq} 는 경로 변수라 본문에 없다. 두 곳이 같은 내용인지는
     * {@code ScanContractTest} 가 {@code contracts/openapi/tracking-service.yaml} 과 대조한다.
     *
     * @param type          스캔 종류
     * @param occurredAt    <strong>시뮬레이션 시각</strong> (불변규칙 12)
     * @param lat           위도. 없으면 필드째 빠진다
     * @param lng           경도
     * @param failureReason {@code FAILED} 의 사유
     */
    record ScanBody(String type, Instant occurredAt, @Nullable Double lat, @Nullable Double lng,
            @Nullable String failureReason) {
    }

    private static ScanBody bodyOf(ScanCall call) {
        return new ScanBody(call.type().name(), call.occurredAt(), call.lat(), call.lng(),
                call.failureReason());
    }

    /** 오류 본문에서 {@code code} 를 꺼낸다. 본문이 JSON 이 아니어도 실행을 멈추지 않는다. */
    private @Nullable String problemCode(@Nullable String body) {
        JsonNode node = tree(body);
        return node != null && node.path("code").isString() ? node.path("code").stringValue() : null;
    }

    /** 200 본문의 {@code orders[].outcome} 을 센다. */
    private Map<String, Integer> outcomes(@Nullable String body) {
        JsonNode node = tree(body);
        if (node == null) {
            return Map.of();
        }
        Map<String, Integer> counts = new HashMap<>();
        for (JsonNode order : node.path("orders")) {
            JsonNode outcome = order.path("outcome");
            if (outcome.isString()) {
                counts.merge(outcome.stringValue(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private @Nullable JsonNode tree(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return json.readTree(body);
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
