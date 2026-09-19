package com.dawnline.sim.driver;

import static com.dawnline.sim.driver.DriverFixtures.DEPARTURE;
import static com.dawnline.sim.driver.DriverFixtures.ROUTE;
import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.json.EventJson;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** 스캔 HTTP 어댑터. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class HttpScanClientTest {

    private static final ScanCall COMPLETED =
            new ScanCall(3, ScanType.COMPLETED, DEPARTURE, 37.51, 127.02, null);

    @Test
    void 받아들여지면_200_이고_주문별_결과를_센다() throws IOException {
        try (LocalScanServer server = LocalScanServer.alwaysAnswering(200, """
                {"routeId":"%s","stopSeq":3,"type":"COMPLETED","orders":[
                  {"orderId":"0199a000-0000-7000-8000-000000000001","outcome":"APPLIED","status":"COMPLETED"},
                  {"orderId":"0199a000-0000-7000-8000-000000000002","outcome":"APPLIED","status":"COMPLETED"},
                  {"orderId":"0199a000-0000-7000-8000-000000000003","outcome":"AFTER_CANCEL","status":"CANCELLED"}]}
                """.formatted(ROUTE))) {

            ScanClient.Response response = server.client().report(ROUTE, COMPLETED);

            assertThat(response.isAccepted()).isTrue();
            assertThat(response.outcomes()).containsEntry("APPLIED", 2).containsEntry("AFTER_CANCEL", 1);
        }
    }

    @Test
    void 아직_모르는_라우트는_404_로_구별된다() throws IOException {
        try (LocalScanServer server = LocalScanServer.alwaysAnswering(404,
                "{\"status\":404,\"code\":\"shipment-not-found\"}")) {

            ScanClient.Response response = server.client().report(ROUTE, COMPLETED);

            assertThat(response.isNotYetKnown()).isTrue();
            assertThat(response.isAccepted()).isFalse();
            assertThat(response.problemCode()).isEqualTo("shipment-not-found");
        }
    }

    @Test
    void 본문이_JSON_이_아니어도_실행을_멈추지_않는다() throws IOException {
        try (LocalScanServer server = LocalScanServer.alwaysAnswering(502, "<html>gateway</html>")) {

            ScanClient.Response response = server.client().report(ROUTE, COMPLETED);

            assertThat(response.status()).isEqualTo(502);
            assertThat(response.problemCode()).isNull();
            assertThat(response.outcomes()).isEmpty();
            assertThat(response.failure()).isNull();
        }
    }

    @Test
    void 붙지_않는_주소는_예외가_아니라_전송_실패_값이다() {
        // 라우트 하나가 죽는 것과 실행이 죽는 것은 전혀 다른 사건이다.
        ScanClient client = new HttpScanClient(HttpClient.newHttpClient(), EventJson.standardMapper(),
                "http://127.0.0.1:1", Duration.ofSeconds(1));

        ScanClient.Response response = client.report(ROUTE, COMPLETED);

        assertThat(response.status()).isZero();
        assertThat(response.failure()).isNotBlank();
        assertThat(response.isAccepted()).isFalse();
    }

    @Test
    void base_url_의_끝_슬래시는_경로를_망가뜨리지_않는다() throws IOException {
        try (LocalScanServer server = LocalScanServer.alwaysAnswering(200, "{}")) {
            ScanClient client = new HttpScanClient(HttpClient.newHttpClient(), EventJson.standardMapper(),
                    server.baseUrl() + "///", Duration.ofSeconds(5));

            client.report(ROUTE, COMPLETED);

            assertThat(server.received().getFirst().path())
                    .isEqualTo("/api/v1/routes/%s/stops/3/events".formatted(ROUTE));
        }
    }

    @Test
    void 시각은_단말이_말한_시각_그대로_나간다() throws IOException {
        // 서버 시각을 쓰면 정시율(§8.1)이 처리 지연만큼 어긋난다. 그리고 시뮬레이션 시각을
        // 벽시계로 바꿔 보내면 "주입한 지연이 곧 편차다" 라는 전제가 조용히 무너진다.
        try (LocalScanServer server = LocalScanServer.alwaysAnswering(200, "{}")) {

            server.client().report(ROUTE, COMPLETED);

            JsonNode body = EventJson.standardMapper().readTree(server.received().getFirst().body());
            assertThat(body.path("occurredAt").stringValue()).isEqualTo(DEPARTURE.toString());
        }
    }

    @Test
    void 위치를_모르면_필드째_보내지_않는다() throws IOException {
        try (LocalScanServer server = LocalScanServer.alwaysAnswering(200, "{}")) {
            ScanCall noPosition = new ScanCall(1, ScanType.DEPARTED_CAMP, DEPARTURE, null, null, null);

            server.client().report(ROUTE, noPosition);

            JsonNode body = EventJson.standardMapper().readTree(server.received().getFirst().body());
            assertThat(body.has("lat")).isFalse();
            assertThat(body.has("lng")).isFalse();
            assertThat(body.has("failureReason")).isFalse();
        }
    }
}
