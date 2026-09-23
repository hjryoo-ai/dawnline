package com.dawnline.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dawnline.common.Ids;
import com.dawnline.tracking.application.TrackingMetrics;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.AssignedStop;
import com.dawnline.tracking.application.port.in.ApplyRouteAssignmentUseCase.RouteAssignment;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
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
 * 기사 스캔 API — HTTP 부터 {@code shipment_events} 까지 (DESIGN.md §5.4, §8.5).
 *
 * <p>배송은 {@code route.assigned} 를 반영하는 <strong>유스케이스로</strong> 만든다. SQL 로
 * 직접 넣으면 스캔이 보는 상태가 실제 소비 경로가 만드는 상태와 어긋나도 이 테스트는 통과한다 —
 * 픽스처가 검사 대상을 대신 정해 버리는 형태다(§13).
 *
 * <p>픽스처 행은 테스트마다 새 id 로 만들고 {@code @AfterEach} 에서 지운다. 공유 시드를 고치고
 * 되돌리는 형태가 아니므로 실행 순서에도 병렬에도 기대지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("기사 스캔 API")
class ScanApiIT extends TrackingIntegrationTestBase {

    /** 캠프는 라우트의 성질이다 — route_revisions 에 남는다 (§9.1 camp 라벨). */
    private static final UUID CAMP = UUID.randomUUID();


    private static final String SCAN_PATH = "/api/v1/routes/%s/stops/%d/events";

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
    private MeterRegistry meters;

    private TransactionTemplate transactions;
    private Instant arrival;
    /** 계획 출발 시각. DEPARTED_CAMP 편차의 기준이다 (§5.4). */
    private Instant departure;
    private Instant promisedEnd;
    private final Set<UUID> createdOrders = new LinkedHashSet<>();
    private final Set<UUID> createdRoutes = new LinkedHashSet<>();

    /**
     * 이 IT 가 자기 자리에서 끈다 (CLAUDE.md — 기반은 이 속성에 의견을 갖지 않는다).
     *
     * <p>파티션 스케줄러는 <strong>끄지 않는다.</strong> 스캔은 {@code shipment_events} 에
     * INSERT 하고 그 테이블에는 DEFAULT 파티션이 없다(§5.4) — 마이그레이션의 부트스트랩이
     * 오늘을 덮고 있으므로 지금은 통하지만, 스케줄러를 끄는 것은 이 IT 가 실제 기동과 다른
     * 전제 위에 서게 만든다.
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void 공유_자원을_끈다(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired
    private com.dawnline.tracking.application.port.out.EventPartitions partitions;

    @BeforeEach
    void setUp() {
        // 전제: 오늘 쓸 파티션이 있다. shipment_events 에는 DEFAULT 파티션이 없어서(§5.4)
        // 범위 밖 INSERT 는 그 자리에서 실패한다 — 그것이 설계다. 마이그레이션이 기동 시점에
        // 창을 만들지만, 같은 컨테이너를 쓰는 ShipmentEventPartitionIT 의 회전 검사가 2035년
        // 기준 시계로 rotate() 를 부르며 보존 경계보다 앞선 파티션을 전부 드롭한다 — 오늘 것을
        // 포함해서. 지금 이 클래스가 통과하는 근거는 **클래스 이름 순서**뿐이고(S-c < S-h),
        // 순서가 근거이면 그 통과는 통과가 아니다(§13 여덟째 축). 쓰는 쪽이 자기 자리에서
        // 만든다 — ensure 는 멱등이다.
        partitions.ensure(java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1), 3);
        transactions = new TransactionTemplate(transactionManager);
        arrival = clock.instant().plus(Duration.ofMinutes(30));
        // 계획 출발은 첫 도착보다 앞이다. 리터럴을 쓰지 않는 이유는 이 값이 스캔 시각과
        // 비교되기 때문이다 — 편차가 거기서 나온다 (CLAUDE.md 「픽스처에 시각 리터럴…」).
        departure = clock.instant().plus(Duration.ofMinutes(5));
        promisedEnd = clock.instant().plus(Duration.ofHours(3));
    }

    @AfterEach
    void 만든_행을_지운다() {
        createdOrders.forEach(orderId -> {
            jdbc.update("DELETE FROM shipment_events WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM shipments WHERE order_id = ?", orderId);
        });
        createdRoutes.forEach(routeId ->
                jdbc.update("DELETE FROM route_revisions WHERE route_id = ?", routeId));
        createdOrders.clear();
        createdRoutes.clear();
    }

    // --- 적용 ---------------------------------------------------------------

    @Test
    void 도착_스캔이_상태를_옮기고_사건을_남긴다() throws Exception {
        UUID route = newRoute();
        UUID order = newOrder();
        assign(route, 1, stop(1, List.of(order), Set.of()));

        mockMvc.perform(scan(route, 1, """
                        {"type":"ARRIVED","occurredAt":"%s","orderIds":["%s"],\
                        "lat":37.4979,"lng":127.0276}"""
                        .formatted(clock.instant(), order)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("ARRIVED"))
                .andExpect(jsonPath("$.stopSeq").value(1))
                .andExpect(jsonPath("$.orders[0].orderId").value(order.toString()))
                .andExpect(jsonPath("$.orders[0].outcome").value("APPLIED"))
                .andExpect(jsonPath("$.orders[0].status").value("ARRIVED"));

        assertThat(statusOf(order)).isEqualTo("ARRIVED");
        assertThat(eventCount(order)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT lat FROM shipment_events WHERE order_id = ?", Double.class, order))
                .isEqualTo(37.4979);
    }

    @Test
    void 한_stop_의_두_주문에_각각_적용된다() throws Exception {
        UUID route = newRoute();
        UUID first = newOrder();
        UUID second = newOrder();
        assign(route, 1, stop(2, List.of(first, second), Set.of()));

        mockMvc.perform(scan(route, 2, completedBody(first, second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2));

        assertThat(statusOf(first)).isEqualTo("COMPLETED");
        assertThat(statusOf(second)).isEqualTo("COMPLETED");
        assertThat(eventCount(first)).isEqualTo(1);
        assertThat(eventCount(second)).isEqualTo(1);
    }

    @Test
    void 완료_스캔은_단말이_말한_시각을_delivered_at_에_쓴다() throws Exception {
        UUID route = newRoute();
        UUID order = newOrder();
        assign(route, 1, stop(1, List.of(order), Set.of()));
        Instant scannedAt = clock.instant().minus(Duration.ofMinutes(9));

        mockMvc.perform(scan(route, 1,
                        "{\"type\":\"COMPLETED\",\"occurredAt\":\"%s\",\"orderIds\":[\"%s\"]}"
                                .formatted(scannedAt, order)))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                "SELECT delivered_at FROM shipments WHERE order_id = ?",
                java.time.OffsetDateTime.class, order).toInstant())
                .isEqualTo(scannedAt);
    }

    @Test
    void 실패_사유는_payload_에_JSONB_로_들어간다() throws Exception {
        UUID route = newRoute();
        UUID order = newOrder();
        assign(route, 1, stop(1, List.of(order), Set.of()));

        mockMvc.perform(scan(route, 1, """
                        {"type":"FAILED","occurredAt":"%s","orderIds":["%s"],\
                        "failureReason":"부재 \\"1층\\""}"""
                        .formatted(clock.instant(), order)))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                "SELECT payload ->> 'failureReason' FROM shipment_events WHERE order_id = ?",
                String.class, order))
                .isEqualTo("부재 \"1층\"");
        assertThat(statusOf(order)).isEqualTo("FAILED");
    }

    // --- 멱등 (§8.5 — 키는 상태 머신이다) --------------------------------------

    @Test
    void 같은_스캔을_다시_보내도_사건이_늘지_않는다() throws Exception {
        // 단말이 타임아웃 뒤 같은 요청을 다시 보내는 경우다. 멱등 키 헤더 없이 안전해야 한다.
        UUID route = newRoute();
        UUID order = newOrder();
        assign(route, 1, stop(1, List.of(order), Set.of()));
        String body = completedBody(order);

        mockMvc.perform(scan(route, 1, body)).andExpect(status().isOk());
        mockMvc.perform(scan(route, 1, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[0].outcome").value("STALE"));

        assertThat(eventCount(order)).isEqualTo(1);
        assertThat(statusOf(order)).isEqualTo("COMPLETED");
    }

    @Test
    void 늦게_온_도착_스캔은_완료를_되돌리지_않는다() throws Exception {
        UUID route = newRoute();
        UUID order = newOrder();
        assign(route, 1, stop(1, List.of(order), Set.of()));
        mockMvc.perform(scan(route, 1, completedBody(order))).andExpect(status().isOk());

        mockMvc.perform(scan(route, 1, bodyOf("ARRIVED", order)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[0].outcome").value("STALE"))
                .andExpect(jsonPath("$.orders[0].status").value("COMPLETED"));

        assertThat(statusOf(order)).isEqualTo("COMPLETED");
    }

    // --- 취소 뒤 스캔 --------------------------------------------------------

    @Test
    void 취소된_주문의_스캔은_200_이고_세어진다() throws Exception {
        // 기사가 취소를 받지 못하고 배송한 경우다. 오류로 답하면 단말이 재시도를 반복하고
        // 그동안 다음 stop 이 밀린다 — 기사가 고칠 수 있는 문제가 아니다.
        UUID route = newRoute();
        UUID cancelled = newOrder();
        UUID alive = newOrder();
        assign(route, 1, stop(1, List.of(cancelled, alive), Set.of(cancelled)));
        double before = scanAfterCancelCount();

        mockMvc.perform(scan(route, 1, completedBody(cancelled, alive)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2));

        assertThat(statusOf(cancelled)).isEqualTo("CANCELLED");
        assertThat(statusOf(alive)).as("같은 stop 이라고 함께 죽지 않는다").isEqualTo("COMPLETED");
        assertThat(eventCount(cancelled)).as("무시한 스캔은 사건이 아니다").isZero();
        assertThat(eventCount(alive)).isEqualTo(1);
        assertThat(scanAfterCancelCount() - before).isEqualTo(1.0);
    }

    // --- 열쇠는 주문이다 (ADR-047 결정 1) ---------------------------------------

    @Test
    void 옮겨간_주문의_스캔은_옮겨간_stop_에_적용된다() throws Exception {
        // 재계획이 주문을 B 로 옮겼고(§6.8 relocate) 기사는 아직 A 의 번호로 찍는다. 번호로
        // 찾으면 404 다 — 그리고 그 404 는 기사가 고칠 수 없으므로 단말이 재시도만 반복한다.
        UUID from = newRoute();
        UUID to = newRoute();
        UUID order = newOrder();
        assign(from, 1, stop(1, List.of(order), Set.of()));
        assign(to, 1, stop(5, List.of(order), Set.of()));
        double before = scanAfterRelocateCount();

        mockMvc.perform(scan(from, 1, completedBody(order)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[0].outcome").value("APPLIED"))
                .andExpect(jsonPath("$.routeId").value(from.toString()));

        assertThat(statusOf(order)).isEqualTo("COMPLETED");
        assertThat(routeOf(order))
                .as("스캔은 사실이지 계획이 아니다 — 옮겨간 자리를 되돌리지 않는다")
                .isEqualTo(to);
        assertThat(seqOf(order)).isEqualTo(5);
        assertThat(scanAfterRelocateCount() - before)
                .as("무시하지 않고 적용한 뒤 센다 — 기사와 개정이 어긋난 창의 크기다")
                .isEqualTo(1.0);
    }

    @Test
    void 캠프_출발은_주문_없이_라우트_전체를_옮긴다() throws Exception {
        // 열쇠가 라우트인 유일한 종류다. 「비어 있으면 400」의 반례가 여기 있어야 그 규칙이
        // 「주문을 싣는 스캔에만 해당한다」로 읽힌다.
        UUID route = newRoute();
        UUID first = newOrder();
        UUID second = newOrder();
        assign(route, 1, stop(1, List.of(first), Set.of()), stop(2, List.of(second), Set.of()));

        mockMvc.perform(scan(route, 1, bodyOf("DEPARTED_CAMP")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2));

        assertThat(statusOf(first)).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(statusOf(second)).isEqualTo("OUT_FOR_DELIVERY");
    }

    @Test
    void 캠프_출발에_주문을_실으면_400_이다() throws Exception {
        UUID route = newRoute();
        UUID order = newOrder();
        assign(route, 1, stop(1, List.of(order), Set.of()));

        mockMvc.perform(scan(route, 1, bodyOf("DEPARTED_CAMP", order)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"));

        assertThat(statusOf(order)).as("거절된 요청은 아무것도 바꾸지 않는다").isEqualTo("SCHEDULED");
    }

    @Test
    void 주문_없는_stop_스캔은_400_이다() throws Exception {
        // 열쇠 없이 온 요청이다. 번호로 대신 찾으면 이 PR 이 없앤 경로가 그대로 살아난다.
        mockMvc.perform(scan(newRoute(), 1, bodyOf("ARRIVED")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"));
    }

    // --- 거절 ---------------------------------------------------------------

    @Test
    void 없는_주문은_404_와_Problem_Details_다() throws Exception {
        // 「그 라우트의 그 순번」이 아니라 「그 송장들」을 못 찾은 것이다 (ADR-047 결정 1).
        mockMvc.perform(scan(newRoute(), 9, bodyOf("ARRIVED", newOrder())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"))
                .andExpect(jsonPath("$.type").exists());
    }

    @Test
    void 종류가_없으면_400_이고_어긋난_필드를_돌려준다() throws Exception {
        mockMvc.perform(scan(newRoute(), 1,
                        "{\"occurredAt\":\"%s\",\"orderIds\":[\"%s\"]}"
                                .formatted(clock.instant(), newOrder())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("type"));
    }

    @Test
    void 사건_시각이_없으면_400_이다() throws Exception {
        // 기본값을 두지 않는다 — 빠뜨린 요청이 조용히 「지금」이 되면 정시율이 어긋난 이유를
        // 아무도 찾을 수 없다 (§8.1).
        mockMvc.perform(scan(newRoute(), 1,
                        "{\"type\":\"ARRIVED\",\"orderIds\":[\"%s\"]}".formatted(newOrder())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("occurredAt"));
    }

    @Test
    void 범위를_벗어난_좌표는_400_이다() throws Exception {
        mockMvc.perform(scan(newRoute(), 1, """
                        {"type":"ARRIVED","occurredAt":"%s","orderIds":["%s"],"lat":91.0}"""
                        .formatted(clock.instant(), newOrder())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lat"));
    }

    @Test
    void 사유는_FAILED_에만_붙는다() throws Exception {
        UUID route = newRoute();
        UUID order = newOrder();
        assign(route, 1, stop(1, List.of(order), Set.of()));

        mockMvc.perform(scan(route, 1, """
                        {"type":"ARRIVED","occurredAt":"%s","orderIds":["%s"],\
                        "failureReason":"부재"}"""
                        .formatted(clock.instant(), order)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"));

        assertThat(statusOf(order)).as("거절된 요청은 아무것도 바꾸지 않는다").isEqualTo("SCHEDULED");
    }

    @Test
    void stop_순번은_1_부터다() throws Exception {
        mockMvc.perform(scan(newRoute(), 0, bodyOf("ARRIVED", newOrder())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"));
    }

    // --- 버전 (ADR-009) -------------------------------------------------------

    @Test
    void 지원하지_않는_버전은_404_가_아니다() throws Exception {
        // 리터럴 v1 로 매핑했다면 경로 매칭에서 먼저 떨어져 404 가 된다. 사실은 「그 버전을
        // 지원하지 않는다」이고, 기사 단말은 404 를 보면 라우트가 사라진 줄 안다.
        mockMvc.perform(post("/api/v2/routes/%s/stops/1/events".formatted(newRoute()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyOf("ARRIVED", newOrder())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 레디니스가_버전_파싱에_걸리지_않는다() throws Exception {
        // ADR-009 결정 3 은 술어(`/api/` 로 시작하는 경로에서만 버전을 찾는다)가 없으면
        // /actuator/health 의 두 번째 세그먼트를 버전으로 파싱하려 들어 프로브가 깨진다고 적는다.
        //
        // **그 음성 표본은 여기서 재현되지 않았다** (2026-09-19, Boot 4.1.x). 술어를 빼고
        // 돌려도 이 검사는 통과한다 — Spring 7 의 버전 리졸버가 버전을 요구하는 매핑이 없을 때
        // 세그먼트를 해석하지 않는 것으로 보인다. 술어는 ADR 이 정한 대로 그대로 두되(의도는
        // 여전히 옳다), 이 검사가 <그 변경>을 막는다고 적지는 않는다. 지금 이 줄이 보장하는 것은
        // 「버저닝을 켠 뒤에도 레디니스가 200 이다」 하나다 (§8.6).
        //
        // order-service 의 같은 주장(OrderApiIT)도 같은 이유로 다시 확인할 필요가 있다.
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    // --- 픽스처 --------------------------------------------------------------

    private org.springframework.test.web.servlet.RequestBuilder scan(UUID routeId, int seq, String body) {
        return post(SCAN_PATH.formatted(routeId, seq))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    /**
     * 스캔 본문. {@code orderIds} 가 <strong>열쇠</strong>다 (ADR-047 결정 1) — 비우고 부르는
     * 자리는 「열쇠 없이 보낸 요청」을 보는 테스트뿐이다.
     */
    private String bodyOf(String type, UUID... orderIds) {
        return "{\"type\":\"%s\",\"occurredAt\":\"%s\",\"orderIds\":[%s]}"
                .formatted(type, clock.instant(), quoted(orderIds));
    }

    private String completedBody(UUID... orderIds) {
        return bodyOf("COMPLETED", orderIds);
    }

    private static String quoted(UUID... orderIds) {
        return Arrays.stream(orderIds).map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(","));
    }

    private void assign(UUID routeId, int revision, AssignedStop... stops) {
        transactions.executeWithoutResult(status ->
                applyRouteAssignment.apply(
                        new RouteAssignment(routeId, revision, CAMP, departure, List.of(stops))));
    }

    private AssignedStop stop(int seq, List<UUID> orderIds, Set<UUID> cancelled) {
        return new AssignedStop(seq, orderIds, cancelled, arrival, promisedEnd);
    }

    private String statusOf(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM shipments WHERE order_id = ?",
                String.class, orderId);
    }

    private int eventCount(UUID orderId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM shipment_events WHERE order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private UUID routeOf(UUID orderId) {
        return jdbc.queryForObject("SELECT route_id FROM shipments WHERE order_id = ?",
                UUID.class, orderId);
    }

    private int seqOf(UUID orderId) {
        Integer seq = jdbc.queryForObject("SELECT stop_seq FROM shipments WHERE order_id = ?",
                Integer.class, orderId);
        return seq == null ? 0 : seq;
    }

    private double scanAfterRelocateCount() {
        try {
            return meters.get(TrackingMetrics.SCAN_AFTER_RELOCATE).counter().count();
        } catch (MeterNotFoundException e) {
            return 0.0;
        }
    }

    private double scanAfterCancelCount() {
        try {
            return meters.get(TrackingMetrics.SCAN_AFTER_CANCEL).counter().count();
        } catch (MeterNotFoundException e) {
            return 0.0;
        }
    }

    private UUID newOrder() {
        UUID orderId = Ids.newId();
        createdOrders.add(orderId);
        return orderId;
    }

    private UUID newRoute() {
        UUID routeId = Ids.newId();
        createdRoutes.add(routeId);
        return routeId;
    }
}
