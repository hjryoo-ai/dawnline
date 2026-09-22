package com.dawnline.sim.driver;

import com.dawnline.messaging.json.EventJson;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * 진짜 소켓 위의 스캔 API. 목으로는 <strong>무엇이 실제로 나갔는지</strong>를 알 수 없다 —
 * 경로의 {@code v1}, 본문의 필드 이름, 시각의 표기는 직렬화를 지나야 드러난다
 * ({@code HttpOrderClientTest} 와 같은 이유, 같은 도구).
 */
final class LocalScanServer implements AutoCloseable {

    /** 서버가 받은 것. */
    record Received(String path, String body) {
    }

    private final HttpServer server;
    private final List<Received> received = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    /**
     * @param responder 몇 번째 호출인지를 받아 {@code [상태, 본문]} 을 돌려준다
     */
    LocalScanServer(IntFunction<Object[]> responder) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Object[] answer = responder.apply(calls.getAndIncrement());
            synchronized (received) {
                received.add(new Received(exchange.getRequestURI().getPath(), body));
            }
            byte[] bytes = String.valueOf(answer[1]).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders((int) answer[0], bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    /** 언제나 같은 답을 주는 서버. */
    static LocalScanServer alwaysAnswering(int status, String body) throws IOException {
        return new LocalScanServer(call -> new Object[] {status, body});
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    ScanClient client() {
        return new HttpScanClient(HttpClient.newHttpClient(), EventJson.standardMapper(),
                baseUrl(), Duration.ofSeconds(5));
    }

    List<Received> received() {
        synchronized (received) {
            return List.copyOf(received);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
