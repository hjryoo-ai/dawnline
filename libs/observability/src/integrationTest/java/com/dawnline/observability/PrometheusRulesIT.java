package com.dawnline.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.observability.docs.MetricsTable;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 알림 규칙을 실제 Prometheus 로 돌린다 (DESIGN.md §9.4, ADR-060 결정 3, 7-0 A5 · A7).
 *
 * <p>둘을 본다.
 * <ol>
 *   <li><strong>promtool 단위 테스트</strong> — {@code deploy/compose/prometheus/tests/} 가 알림마다 울리는 경우와 울리지 않는
 *       경우를 적었다. 표본은 손으로 적은 것이다.</li>
 *   <li><strong>§9.1 「짝」의 재현</strong> — 이번에는 표본을 손으로 적지 않는다. 실제 Micrometer 레지스트리를 실제 Prometheus
 *       가 긁는다. 미리 등록한 카운터와 처음 셀 때 만든 카운터를 <em>같은 순간</em> 올리면 {@code increase()} 는 앞의 것만
 *       읽는다 — 1 로 태어난 시계열의 첫 증가는 앞 표본이 없어 0 이다. 같은 실행에서 규칙 파일의
 *       {@code DawnlineCancelTooLate} 는 뒤의 것에 울린다(부재를 다루는 식). 이 두 줄이 §9.1 의 근거를 「추정」에서
 *       「관측(재현됨)」으로 올린 것이다.</li>
 * </ol>
 *
 * <p>이미지 태그는 {@code deploy/compose/.env.example} 의 {@code PROMETHEUS_IMAGE} 다 — Compose 가 띄우는 것과 같은 판에서 본다.
 */
class PrometheusRulesIT {

    private static final Path REPO = MetricsTable.locateRepoRoot();
    private static final Path PROMETHEUS_DIR = REPO.resolve("deploy/compose/prometheus");
    private static final DockerImageName IMAGE = DockerImageName.parse(composeImage("PROMETHEUS_IMAGE"));

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PrometheusMeterRegistry registry;
    private HttpServer server;

    @BeforeEach
    void 테스트의_레지스트리를_HTTP_로_낸다() throws IOException {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/metrics", exchange -> {
            byte[] body = registry.scrape().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void 멈춘다() {
        server.stop(0);
        registry.close();
    }

    @Test
    void promtool_단위_테스트가_통과한다() {
        try (GenericContainer<?> promtool = new GenericContainer<>(IMAGE)
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("promtool"))
                .withCommand("test", "rules", "/p/tests/dawnline-alerts.test.yml")
                .withCopyFileToContainer(MountableFile.forHostPath(PROMETHEUS_DIR), "/p")
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)))) {
            try {
                promtool.start();
            } catch (ContainerLaunchException e) {
                throw new AssertionError("promtool test rules 가 실패했다:\n" + promtool.getLogs(), e);
            }
            assertThat(promtool.getLogs()).contains("SUCCESS");
        }
    }

    @Test
    void 처음_셀_때_태어난_카운터의_첫_증가를_increase_는_못_읽고_규칙은_읽는다() {
        // 닫힌 카운터 — 기동 때 0 으로 미리 등록한다. 열린 카운터(camp)는 첫 사건 전에는 시계열이 없다.
        DawnlineMeters.preregister(registry, DawnlineMetrics.INTERNAL_TOKEN_REJECTED);

        int port = server.getAddress().getPort();
        Testcontainers.exposeHostPorts(port);
        String config = """
                global:
                  scrape_interval: 1s
                  evaluation_interval: 1s
                rule_files:
                  - /etc/prometheus/rules/dawnline-alerts.yml
                scrape_configs:
                  - job_name: probe
                    metrics_path: /metrics
                    static_configs:
                      - targets: ["host.testcontainers.internal:%d"]
                """.formatted(port);

        try (GenericContainer<?> prometheus = new GenericContainer<>(IMAGE)
                .withCopyToContainer(Transferable.of(config), "/etc/prometheus/prometheus.yml")
                .withCopyFileToContainer(MountableFile.forHostPath(PROMETHEUS_DIR.resolve("rules")), "/etc/prometheus/rules")
                .withExposedPorts(9090)
                .waitingFor(Wait.forHttp("/-/ready").forStatusCode(200))) {
            prometheus.start();
            String base = "http://" + prometheus.getHost() + ":" + prometheus.getMappedPort(9090);

            // 전제 — 미리 등록한 쪽은 사건 전에 0 인 표본이 여럿 있다.
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(
                    scalar(base, "count_over_time(dawnline_internal_token_rejected_total{reason=\"missing\"}[1m])"))
                    .isGreaterThanOrEqualTo(3));
            assertThat(query(base, "dawnline_cancel_too_late_total")).as("전제 — 열린 카운터는 사건 전에 시계열이 없다")
                    .isEmpty();

            // 같은 순간 — 하나는 미리 등록한 카운터, 하나는 처음 셀 때 만든 카운터.
            DawnlineMeters.counter(registry, DawnlineMetrics.INTERNAL_TOKEN_REJECTED, "reason", "missing").increment();
            DawnlineMeters.counter(registry, DawnlineMetrics.CANCEL_TOO_LATE, "camp", "c1").increment();

            // 전제 — 태어난 쪽도 표본이 여럿 쌓였다. 아래의 0 이 「없음」이 아니라 「읽지 못함」이어야 한다.
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(
                    scalar(base, "count_over_time(dawnline_cancel_too_late_total[1m])")).isGreaterThanOrEqualTo(4));

            assertThat(scalar(base, "increase(dawnline_internal_token_rejected_total{reason=\"missing\"}[1m])"))
                    .as("미리 등록한 카운터의 첫 증가는 읽힌다").isGreaterThan(0.5);
            assertThat(scalar(base, "increase(dawnline_cancel_too_late_total[1m])"))
                    .as("1 로 태어난 카운터의 첫 증가는 0 으로 읽힌다 — §9.1 「짝」, 근거: 관측(재현됨)").isZero();
            assertThat(query(base, "increase(dawnline_cancel_too_late_total[10m]) > 0"))
                    .as("increase 만으로 쓴 알림 식은 첫 사건에 울리지 않는다").isEmpty();

            // 규칙 파일의 식은 부재를 다룬다 — 같은 사건에 울린다.
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                    query(base, "ALERTS{alertname=\"DawnlineCancelTooLate\", alertstate=\"firing\", camp=\"c1\"}"))
                    .hasSize(1));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                    query(base, "ALERTS{alertname=\"DawnlineInternalTokenRejected\", alertstate=\"firing\"}"))
                    .hasSize(1));
        }
    }

    /** 쿼리 결과의 값 하나. 결과가 없으면 실패한다 — 「없음」을 0 으로 읽지 않는다. */
    private static double scalar(String base, String promql) {
        List<JsonNode> result = query(base, promql);
        assertThat(result).as("결과가 하나여야 한다: %s", promql).hasSize(1);
        return Double.parseDouble(result.getFirst().get("value").get(1).asString());
    }

    private static List<JsonNode> query(String base, String promql) {
        URI uri = URI.create(base + "/api/v1/query?query=" + URLEncoder.encode(promql, StandardCharsets.UTF_8));
        try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode body = JSON.readTree(response.body());
            assertThat(body.get("status").asString()).as("쿼리 %s → %s", promql, response.body()).isEqualTo("success");
            List<JsonNode> result = new ArrayList<>();
            body.get("data").get("result").forEach(result::add);
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Compose 가 쓰는 이미지 태그 — 버전은 {@code .env} 에서만 고정한다(CLAUDE.md). 커밋된 판은 {@code .env.example}. */
    private static String composeImage(String key) {
        try {
            for (String line : Files.readAllLines(REPO.resolve("deploy/compose/.env.example"), StandardCharsets.UTF_8)) {
                if (line.startsWith(key + "=")) {
                    return line.substring(key.length() + 1).strip();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new IllegalStateException(".env.example 에 " + key + " 가 없다");
    }
}
