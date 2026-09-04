package com.dawnline.sim.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP 어댑터. JDK 의 {@link HttpServer} 로 진짜 소켓 위에서 확인한다 — 헤더가 실제로 나가는지,
 * 오류 본문에서 {@code code} 를 꺼내는지는 목으로는 알 수 없다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class HttpOrderClientTest {

    private static final GeneratedOrder ORDER = new GeneratedOrder(
            UUID.fromString("00000000-0000-4000-8000-000000000001"),
            "DAWN", "테스트로 1길 1", "06000",
            new GeneratedOrder.Parcel(1000, 5000, true, false),
            List.of(new GeneratedOrder.Item("SKU-00001", 2)));

    /** 서버가 실제로 받은 것. {@code HttpExchange} 를 들고 있지 않으려고 값으로 옮긴다. */
    private record Received(String path, String idempotencyKey, String contentType, String body) {
    }

    private final List<Received> received = new ArrayList<>();

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 주어진 응답을 돌려주는 서버를 띄우고 그 주소를 준다. */
    private String serve(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/orders", exchange -> {
            received.add(new Received(
                    exchange.getRequestURI().getPath(),
                    String.valueOf(exchange.getRequestHeaders().getFirst("Idempotency-Key")),
                    String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static OrderClient clientFor(String baseUrl) {
        return new HttpOrderClient(HttpClient.newHttpClient(), JsonMapper.builder().build(),
                baseUrl, Duration.ofSeconds(5));
    }

    @Test
    void 접수되면_201_이고_멱등_키가_헤더로_나간다() throws IOException {
        OrderClient client = clientFor(serve(201, "{}"));

        OrderClient.Response response = client.place(ORDER, "key-1");

        assertThat(response.isAccepted()).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.getFirst().idempotencyKey()).isEqualTo("key-1");
        assertThat(received.getFirst().contentType()).isEqualTo("application/json");
    }

    @Test
    void 오류_본문에서_Problem_Details_의_code_를_꺼낸다() throws IOException {
        OrderClient client = clientFor(serve(422,
                "{\"type\":\"https://dawnline.internal/problems/tier-not-serviceable\","
                        + "\"status\":422,\"code\":\"tier-not-serviceable\"}"));

        OrderClient.Response response = client.place(ORDER, "key-2");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.problemCode()).isEqualTo("tier-not-serviceable");
        assertThat(response.isAccepted()).isFalse();
    }

    @Test
    void 본문이_JSON_이_아니어도_실행을_멈추지_않는다() throws IOException {
        OrderClient client = clientFor(serve(502, "<html>gateway</html>"));

        OrderClient.Response response = client.place(ORDER, "key-3");

        assertThat(response.status()).isEqualTo(502);
        assertThat(response.problemCode()).isNull();
        assertThat(response.failure()).isNull();
    }

    @Test
    void 붙지_않는_주소는_예외가_아니라_전송_실패_값이다() {
        // 실행이 3번째 주문에서 죽는 것과 200건 중 3건이 실패한 것은 전혀 다른 사건이다.
        OrderClient client = new HttpOrderClient(HttpClient.newHttpClient(),
                JsonMapper.builder().build(), "http://127.0.0.1:1", Duration.ofSeconds(1));

        OrderClient.Response response = client.place(ORDER, "key-4");

        assertThat(response.status()).isZero();
        assertThat(response.failure()).isNotBlank();
        assertThat(response.isAccepted()).isFalse();
    }

    @Test
    void base_url_의_끝_슬래시는_경로를_망가뜨리지_않는다() throws IOException {
        OrderClient client = clientFor(serve(201, "{}") + "///");

        OrderClient.Response response = client.place(ORDER, "key-5");

        assertThat(response.isAccepted()).isTrue();
        assertThat(received.getFirst().path()).isEqualTo("/api/v1/orders");
    }

    @Test
    void 본문은_계약이_요구하는_필드로_직렬화된다() throws IOException {
        OrderClient client = clientFor(serve(201, "{}"));

        client.place(ORDER, "key-6");

        JsonNode body = JsonMapper.builder().build().readTree(received.getFirst().body());
        assertThat(body.propertyNames()).contains(
                "customerId", "serviceTier", "addressLine", "postalCode", "parcel", "items");
        // 주문 id·접수 시각·약속 배송창은 서버가 정한다 (PlaceOrderRequest 의 javadoc).
        assertThat(body.has("orderId")).isFalse();
        assertThat(body.has("placedAt")).isFalse();
        assertThat(body.path("parcel").path("requiresCold").booleanValue()).isTrue();
    }
}
