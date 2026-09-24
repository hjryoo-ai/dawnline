package com.dawnline.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dawnline.ops.application.OnTimeRatioGauges;
import com.dawnline.ops.application.port.in.Fact;
import com.dawnline.ops.application.port.in.ProjectFactUseCase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 읽기 모델 조회가 실제 PostgreSQL 에서 그 행을 고르는지 (DESIGN.md §5.5 「조회」). 판정 로직은
 * {@code ReadModelQueryServiceTest}, 예외 목록의 계획은 {@code KpiViewsIndexIT} 가 본다.
 *
 * <p>픽스처는 되돌리지 않고 만들고 지운다(CLAUDE.md) — 이 클래스만 쓰는 캠프 id 둘로 만들고 그 캠프로 지운다. 시각은
 * 주입된 시계에서 뽑는다: 조회가 그 값을 지금과 견준다(창).
 */
@SpringBootTest(classes = OpsApplication.class)
@AutoConfigureMockMvc
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("ReadSurfaceIT — 대시보드와 지도의 조회")
class ReadSurfaceIT extends OpsIntegrationTestBase {

    private static final UUID CAMP = UUID.fromString("0199c000-0000-7000-8000-00000000ca01");
    private static final UUID OTHER_CAMP = UUID.fromString("0199c000-0000-7000-8000-00000000ca02");
    private static final String VIEWER = "Bearer " + OpsTokens.token("OPS_VIEWER", "it-read-surface");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private OnTimeRatioGauges gauges;

    @Autowired
    private ProjectFactUseCase projector;

    @Autowired
    private TransactionTemplate transactions;

    @DynamicPropertySource
    static void 공유_자원을_끈다(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        // 게이지는 테스트가 부를 때만 센다 — 스케줄러가 끼어들면 「같은 창」의 비교가 흔들린다.
        registry.add("dawnline.ops.kpi.on-time-initial-delay-ms", () -> "3600000");
    }

    @AfterEach
    void 자기_행을_지운다() {
        for (String table : new String[] {"rm_orders", "rm_routes", "rm_waves"}) {
            jdbc.update("DELETE FROM " + table + " WHERE camp_id IN (?, ?)", CAMP, OTHER_CAMP);
        }
    }

    @Test
    void 웨이브는_캠프와_컷오프_창으로_고른다() throws Exception {
        Instant now = clock.instant();
        UUID inside = wave(CAMP, now.plus(Duration.ofHours(1)), null, null);
        UUID outside = wave(CAMP, now.minus(Duration.ofHours(30)), null, null);
        wave(OTHER_CAMP, now.plus(Duration.ofHours(1)), null, null);

        mockMvc.perform(viewer(get("/api/v1/camps")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.camps[?(@.campId == '" + CAMP + "')].waves").value(2));
        mockMvc.perform(viewer(get("/api/v1/camps/{campId}/waves", CAMP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waves.length()").value(1))
                .andExpect(jsonPath("$.waves[0].waveId").value(inside.toString()))
                .andExpect(jsonPath("$.waves[0].status").value("OPEN"));
        mockMvc.perform(viewer(get("/api/v1/camps/{campId}/waves", CAMP)
                        .param("from", now.minus(Duration.ofDays(2)).toString())
                        .param("to", now.plus(Duration.ofDays(1)).toString())))
                .andExpect(jsonPath("$.waves.length()").value(2))
                .andExpect(jsonPath("$.waves[0].waveId").value(outside.toString()));
        mockMvc.perform(viewer(get("/api/v1/camps/{campId}/waves", CAMP)
                        .param("from", now.toString()).param("to", now.plus(Duration.ofDays(8)).toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"));
    }

    @Test
    void KPI_조회는_게이지와_같은_수를_말한다() throws Exception {
        // 완료 4(정시 3) + 실패 1 → 원 약속 기준 3/5. 취소는 배송됐어도 분모에 없다(예외 목록의 행이다).
        Instant delivered = clock.instant().minus(Duration.ofMinutes(30));
        for (int i = 0; i < 3; i++) {
            order(CAMP, "DISPATCHED", "COMPLETED", delivered, delivered.plus(Duration.ofHours(1)));
        }
        order(CAMP, "DISPATCHED", "COMPLETED", delivered, delivered.minus(Duration.ofHours(1)));
        order(CAMP, "DISPATCHED", "FAILED", delivered, delivered.plus(Duration.ofHours(1)));
        order(CAMP, "CANCELLED", "COMPLETED", delivered, delivered.plus(Duration.ofHours(1)));
        gauges.refreshNow();
        double gauge = gauges.ratio(CAMP, OnTimeRatioGauges.Basis.PROMISED);

        assertThat(gauge).as("전제 — 게이지가 이 캠프를 셌다").isEqualTo(0.6);
        mockMvc.perform(viewer(get("/api/v1/kpi/delivery")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.camps[?(@.campId == '" + CAMP + "')].onTimeRatioPromised").value(gauge))
                .andExpect(jsonPath("$.camps[?(@.campId == '" + CAMP + "')].delivered").value(4))
                .andExpect(jsonPath("$.camps[?(@.campId == '" + CAMP + "')].failed").value(1));
    }

    @Test
    void 예외_목록은_취소됐는데_배송된_주문만_KPI_창_안에서_고른다() throws Exception {
        Instant now = clock.instant();
        UUID listed = order(CAMP, "CANCELLED", "COMPLETED", now.minus(Duration.ofHours(2)), now);
        order(CAMP, "CANCELLED", "COMPLETED", now.minus(Duration.ofDays(3)), now);   // 창 밖
        order(CAMP, "CANCELLED", "FAILED", now.minus(Duration.ofHours(2)), now);     // 배송되지 않았다
        order(CAMP, "DISPATCHED", "COMPLETED", now.minus(Duration.ofHours(2)), now); // 취소되지 않았다
        order(OTHER_CAMP, "CANCELLED", "COMPLETED", now.minus(Duration.ofHours(2)), now);

        mockMvc.perform(viewer(get("/api/v1/camps/{campId}/exceptions", CAMP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].orderId").value(listed.toString()))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void 웨이브의_라우트는_계획의_라우트와_창고를_싣는다() throws Exception {
        Instant now = clock.instant();
        UUID plan = UUID.randomUUID();
        UUID planned = wave(CAMP, now, plan, new double[] {37.5012, 127.0396});
        UUID first = route(plan, true);
        UUID second = route(plan, null);
        route(UUID.randomUUID(), false);   // 다른 계획
        UUID unplanned = wave(CAMP, now, null, null);

        mockMvc.perform(viewer(get("/api/v1/waves/{waveId}/routes", planned)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value(plan.toString()))
                .andExpect(jsonPath("$.depot.lat").value(37.5012))
                .andExpect(jsonPath("$.depot.lng").value(127.0396))
                .andExpect(jsonPath("$.routes.length()").value(2))
                .andExpect(jsonPath("$.routes[?(@.routeId == '" + first + "')].atRisk").value(true))
                // at-risk 가 온 적이 없으면 false 가 아니라 null — false 는 「위험하지 않다」는 주장이다.
                .andExpect(jsonPath("$.routes[?(@.routeId == '" + second + "')].atRisk").value((Object) null));
        mockMvc.perform(viewer(get("/api/v1/waves/{waveId}/routes", unplanned)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes.length()").value(0))
                .andExpect(jsonPath("$.depot").doesNotExist())
                // doesNotExist 는 「없다」와 「null」을 가르지 않는다. 문서(OpenApiContractIT)는 「있고 null」이라고
                // 말하므로 본문이 그것인지 여기서 본다 — 칸이 빠지면 TS 쪽의 null 검사가 헛돈다.
                .andExpect(result -> {
                    JsonNode body = JsonMapper.builder().build().readTree(result.getResponse().getContentAsString());
                    assertThat(body.has("depot") && body.get("depot").isNull()).as("depot 는 있고 null").isTrue();
                    assertThat(body.has("planId") && body.get("planId").isNull()).as("planId 는 있고 null").isTrue();
                });
        mockMvc.perform(viewer(get("/api/v1/waves/{waveId}/routes", UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"));
    }

    @Test
    void wave_closed_가_창고를_채우고_먼저_온_것이_남는다() throws Exception {
        // 키 계열(ColumnFamily.KEY) — 웨이브의 불변 속성이다. 두 번째 사실이 다른 값을 실어도 덮지 않는다.
        UUID waveId = UUID.randomUUID();
        Instant cutoff = clock.instant();
        transactions.executeWithoutResult(status -> projector.project(
                new Fact.WaveClosed(waveId, CAMP, "DAWN", cutoff, 37.5, 127.0)));
        transactions.executeWithoutResult(status -> projector.project(
                new Fact.WaveClosed(waveId, CAMP, "DAWN", cutoff, 35.1, 129.0)));

        mockMvc.perform(viewer(get("/api/v1/waves/{waveId}/routes", waveId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.depot.lat").value(37.5))
                .andExpect(jsonPath("$.depot.lng").value(127.0));
    }

    private MockHttpServletRequestBuilder viewer(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", VIEWER);
    }

    private UUID wave(UUID campId, Instant cutoffAt, UUID planId, double[] depot) {
        UUID waveId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rm_waves (wave_id, camp_id, service_tier, cutoff_at, status, plan_id, depot_lat, depot_lng)
                VALUES (?, ?, 'DAWN', ?, ?, ?, ?, ?)
                """, waveId, campId, cutoffAt.atOffset(ZoneOffset.UTC), planId == null ? "OPEN" : "PLANNED", planId,
                depot == null ? null : depot[0], depot == null ? null : depot[1]);
        return waveId;
    }

    private UUID route(UUID planId, Boolean atRisk) {
        UUID routeId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rm_routes (route_id, plan_id, camp_id, revision, status, stop_count, at_risk)
                VALUES (?, ?, ?, 1, 'ASSIGNED', 12, ?)
                """, routeId, planId, CAMP, atRisk);
        return routeId;
    }

    private UUID order(UUID campId, String orderStatus, String outcome, Instant at, Instant promisedEnd) {
        UUID orderId = UUID.randomUUID();
        boolean completed = "COMPLETED".equals(outcome);
        jdbc.update("""
                INSERT INTO rm_orders (order_id, order_status, delivery_outcome, camp_id, promised_end_original,
                                       promised_end_revised, delivered_at, failed_at, placed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, orderId, orderStatus, outcome, campId, promisedEnd.atOffset(ZoneOffset.UTC),
                promisedEnd.atOffset(ZoneOffset.UTC), completed ? at.atOffset(ZoneOffset.UTC) : null,
                completed ? null : at.atOffset(ZoneOffset.UTC), at.minus(Duration.ofHours(6)).atOffset(ZoneOffset.UTC));
        return orderId;
    }
}
