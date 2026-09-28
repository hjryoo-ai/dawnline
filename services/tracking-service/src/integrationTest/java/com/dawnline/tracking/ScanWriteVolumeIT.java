package com.dawnline.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dawnline.common.Ids;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.AssignedStop;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.RouteAssignment;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 스캔 하나가 쓰는 행 수는 라우트 길이에 비례하지 않는다 (7-0 B11, 리포트 §3.3,
 * [ADR-070](docs/adr/ADR-070-tracking-writes-lock-the-route-first.md) 결정 2).
 *
 * <p>정정 전(편차를 뒤 stop 의 {@code eta_at} 마다 적었다)에는 이 라우트에서 {@code shipments} 갱신이 <strong>990</strong> 이었다 — 배송당 33,
 * 라우트당 O(n²). 정정 뒤 90(배송마다 출발 · 도착 · 완료) · 라우트 행 61(스캔마다 편차 하나).
 *
 * <p>행 갱신을 <strong>테스트 전용 행 트리거</strong>로 센다. {@code pg_stat_user_tables} 는 백엔드가 쉬는 사이에 비동기로 모여서 테스트의
 * 경계와 맞지 않는다 — 트리거는 커밋된 갱신만, 행 하나마다 센다(롤백된 시도의 행은 카운터 행과 함께 되돌아간다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("스캔의 쓰기 양")
class ScanWriteVolumeIT extends TrackingIntegrationTestBase {

    private static final UUID CAMP = UUID.randomUUID();

    private static final String SCAN_PATH = "/api/v1/routes/%s/stops/%d/events";

    /** 라우트 길이. 평일 라우트(행당 갱신 88)에 가까운 크기다. */
    private static final int STOPS = 30;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplyRouteAssignmentUseCase applyRouteAssignment;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @Autowired
    private com.dawnline.tracking.application.port.out.EventPartitions partitions;

    private final List<UUID> orders = new ArrayList<>();
    private UUID route;

    @DynamicPropertySource
    static void 공유_자원을_끈다(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @BeforeEach
    void 세는_트리거를_건다() {
        partitions.ensure(LocalDate.now(ZoneOffset.UTC).minusDays(1), 3);
        jdbc.execute("CREATE TABLE IF NOT EXISTS it_row_writes (tbl TEXT PRIMARY KEY, n BIGINT NOT NULL)");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION it_count_row_write() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  INSERT INTO it_row_writes (tbl, n) VALUES (TG_TABLE_NAME, 1)
                  ON CONFLICT (tbl) DO UPDATE SET n = it_row_writes.n + 1;
                  RETURN NULL;
                END $$""");
        jdbc.execute("CREATE TRIGGER it_count_shipments AFTER UPDATE ON shipments FOR EACH ROW EXECUTE FUNCTION it_count_row_write()");
        jdbc.execute("CREATE TRIGGER it_count_revisions AFTER UPDATE ON route_revisions FOR EACH ROW EXECUTE FUNCTION it_count_row_write()");
    }

    @AfterEach
    void 트리거와_행을_지운다() {
        jdbc.execute("DROP TRIGGER IF EXISTS it_count_shipments ON shipments");
        jdbc.execute("DROP TRIGGER IF EXISTS it_count_revisions ON route_revisions");
        jdbc.execute("DROP TABLE IF EXISTS it_row_writes");
        jdbc.execute("DROP FUNCTION IF EXISTS it_count_row_write()");
        orders.forEach(orderId -> {
            jdbc.update("DELETE FROM shipment_events WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM shipments WHERE order_id = ?", orderId);
        });
        if (route != null) {
            jdbc.update("DELETE FROM route_revisions WHERE route_id = ?", route);
        }
    }

    @Test
    void 라우트_하나를_끝까지_돌린_쓰기는_배송마다_상태_전이_셋이다() throws Exception {
        route = Ids.newId();
        Instant departure = clock.instant().plus(Duration.ofMinutes(5));
        Instant firstArrival = departure.plus(Duration.ofMinutes(20));
        List<AssignedStop> stops = new ArrayList<>();
        for (int seq = 1; seq <= STOPS; seq++) {
            UUID orderId = Ids.newId();
            orders.add(orderId);
            stops.add(new AssignedStop(seq, List.of(orderId), Set.of(),
                    firstArrival.plus(Duration.ofMinutes(10L * (seq - 1))), firstArrival.plus(Duration.ofHours(8))));
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> applyRouteAssignment.apply(
                new RouteAssignment(route, 1, CAMP, departure, stops)));
        jdbc.update("DELETE FROM it_row_writes");

        int scans = 0;
        scan(route, 1, "DEPARTED_CAMP", departure.plus(Duration.ofMinutes(3)), List.of());
        scans++;
        for (int seq = 1; seq <= STOPS; seq++) {
            // 편차가 stop 마다 달라진다 — 그래야 전파가 매번 뒤 stop 을 옮긴다(운영의 모양이다).
            Instant arrived = stops.get(seq - 1).plannedArrival().plus(Duration.ofSeconds(180L + 7L * seq));
            scan(route, seq, "ARRIVED", arrived, List.of(orders.get(seq - 1)));
            scan(route, seq, "COMPLETED", arrived.plus(Duration.ofMinutes(2)), List.of(orders.get(seq - 1)));
            scans += 2;
        }

        long shipmentWrites = writesOf("shipments");
        long revisionWrites = writesOf("route_revisions");
        assertThat(shipmentWrites).as("배송마다 출발 · 도착 · 완료 셋 — 뒤 stop 을 다시 쓰지 않는다").isEqualTo(3L * STOPS);
        assertThat(revisionWrites).as("편차는 라우트 행에 스캔마다 많아야 한 번").isLessThanOrEqualTo(scans);
    }

    private void scan(UUID routeId, int seq, String type, Instant occurredAt, List<UUID> orderIds) throws Exception {
        String ids = String.join(",", orderIds.stream().map(id -> "\"" + id + "\"").toList());
        mockMvc.perform(post(SCAN_PATH.formatted(routeId, seq))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"%s\",\"occurredAt\":\"%s\",\"orderIds\":[%s]}".formatted(type, occurredAt, ids)))
                .andExpect(status().isOk());
    }

    private long writesOf(String table) {
        List<Long> n = jdbc.queryForList("SELECT n FROM it_row_writes WHERE tbl = ?", Long.class, table);
        return n.isEmpty() ? 0 : n.getFirst();
    }
}
