package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.port.in.CancelOrderUseCase;
import com.dawnline.dispatch.application.port.in.PlanView;
import com.dawnline.dispatch.application.port.in.ReassignStopUseCase;
import com.dawnline.dispatch.application.port.in.RouteView;
import com.dawnline.dispatch.application.port.in.RunPlanCommand;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.application.port.out.PlanQueries;
import com.dawnline.dispatch.domain.DispatchCandidate;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 취소가 <strong>실물 PostgreSQL 에서</strong> 무엇을 바꾸는가 (Phase 3-6, §6.10, ADR-026).
 *
 * <p>단위 테스트({@code CancelOrderServiceTest})는 네 분기의 <em>판단</em>을 본다. 여기서 보는
 * 것은 그 판단이 SQL 로 옮겨졌을 때의 결과다 — 어떤 행이 바뀌고, 어떤 순번이 <em>안</em> 바뀌고,
 * 그 다음에 오는 재배정이 그 상태 위에서도 도는가. 마지막 것이 특히 그렇다: 취소된 stop 은
 * 계획에서 빠지므로 순번 재부여가 그 자리를 비워 두면 커밋에서 유일성이 깨진다.
 */
@SpringBootTest(classes = DispatchApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("DispatchCancellationIT — §6.10 취소")
class DispatchCancellationIT extends DispatchIntegrationTestBase {

    private static final tools.jackson.databind.ObjectMapper JSON =
            new tools.jackson.databind.ObjectMapper();

    /** 시드의 첫 캠프 (서울 북부). */
    private static final UUID CAMP_ID = UUID.fromString("01a06edd-6c00-7000-8001-000000000001");
    private static final GeoPoint CAMP = GeoPoint.of(37.640000, 127.030000);

    @Autowired
    private RunPlanUseCase runPlan;

    @Autowired
    private CancelOrderUseCase cancelOrder;

    @Autowired
    private ReassignStopUseCase reassign;

    @Autowired
    private DispatchCandidateRepository candidates;

    @Autowired
    private PlanQueries planQueries;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 릴레이는 끈다 — 검사 대상은 발행이 아니라 SQL 이다. {@code DispatchAdminIT} 와 같은 이유. */
    @DynamicPropertySource
    static void relayOff(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void clean() {
        tx().executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM plan_explanations").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_stop_orders").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_stops").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM routes").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_plans").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM dispatch_candidates").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM outbox_events").executeUpdate();
        });
    }

    @Test
    void 계획_전_취소는_후보만_바꾸고_행을_남긴다() {
        UUID waveId = Ids.newId();
        UUID orderId = seedCandidates(waveId, 1).getFirst();

        CancelOrderUseCase.Outcome outcome =
                tx().execute(status -> cancelOrder.cancel(orderId, Instant.now()));

        assertThat(outcome).isEqualTo(CancelOrderUseCase.Outcome.CANDIDATE_CANCELLED);
        DispatchCandidate stored =
                tx().execute(status -> candidates.findById(orderId)).orElseThrow();
        assertThat(stored.status().name()).isEqualTo("CANCELLED");
        assertThat(outboxCount()).as("아직 아무도 이 주문을 모른다").isZero();
    }

    @Test
    void 발행된_라우트의_취소가_stop_과_시각과_개정을_바꾼다() {
        Planned planned = plannedRoute();
        UUID target = planned.lastStopOrderId();
        Instant before = arrivalOfLastStop(planned.routeId());

        CancelOrderUseCase.Outcome outcome =
                tx().execute(status -> cancelOrder.cancel(target, Instant.now()));

        assertThat(outcome).isEqualTo(CancelOrderUseCase.Outcome.ROUTE_REVISED);
        assertThat(stopStatusOf(target)).isEqualTo("CANCELLED");
        assertThat(revisionOf(planned.routeId())).isEqualTo(2);
        // 마지막 stop 을 죽였으므로 그 앞 stop 들의 시각은 그대로다. 바뀌는 것은 라우트 요약이다
        // — 복귀 거리가 짧아진다.
        assertThat(distanceOf(planned.routeId())).isLessThan(planned.distanceM());
        assertThat(arrivalOfLastStop(planned.routeId()))
                .as("취소된 stop 의 시각은 손대지 않는다").isEqualTo(before);
    }

    @Test
    void 가운데_stop_을_취소하면_뒤_stop_의_도착이_당겨진다() {
        // retime 의 UPDATE 가 실제로 도는지는 여기서만 보인다. 마지막 stop 을 취소하면 남은
        // stop 들의 시각이 그대로라 SQL 이 죽어 있어도 초록이 된다.
        Planned planned = plannedRoute();
        List<UUID> stopOrders = orderIdsBySeq(planned.routeId());
        assertThat(stopOrders).as("가운데를 고르려면 stop 이 셋 이상이어야 한다").hasSizeGreaterThan(2);
        UUID middle = stopOrders.get(stopOrders.size() / 2);
        Instant lastBefore = arrivalOfLastStop(planned.routeId());

        tx().execute(status -> cancelOrder.cancel(middle, Instant.now()));

        assertThat(arrivalOfLastStop(planned.routeId())).isBefore(lastBefore);
        assertThat(stopStatusOf(middle)).isEqualTo("CANCELLED");
    }

    @Test
    void 취소된_stop_은_페이로드에_남고_순번도_그대로다() {
        Planned planned = plannedRoute();
        List<Integer> before = seqsOf(planned.routeId());

        tx().execute(status -> cancelOrder.cancel(planned.lastStopOrderId(), Instant.now()));

        assertThat(seqsOf(planned.routeId())).as("기사가 보던 순번이다 (§6.10)").isEqualTo(before);

        JsonNode payload = JSON.readTree(lastRouteAssigned(planned.routeId()));
        assertThat(payload.get("revision").asInt()).isEqualTo(2);
        JsonNode stops = payload.get("stops");
        assertThat(stops.size()).isEqualTo(before.size());
        assertThat(payload.get("summary").get("stopCount").asInt()).isEqualTo(stops.size());

        JsonNode cancelled = stops.get(stops.size() - 1);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("cancelledOrderIds").get(0).asString())
                .isEqualTo(planned.lastStopOrderId().toString());
    }

    @Test
    void 취소_뒤_재배정도_순번이_1부터_연속이다() {
        // 취소된 stop 은 계획에서 빠진다. 재배정이 살아 있는 stop 에만 1..n 을 다시 매기면
        // 취소된 stop 의 옛 순번이 그 안에 겹쳐 커밋에서 유일성이 깨진다 — 지연 제약은 실패를
        // 미룰 뿐 없애지 않는다. 어댑터가 취소된 stop 을 뒤로 보내는 것이 그 답이고, 이 테스트가
        // 그것을 붙잡는다.
        TwoRoutes routes = twoRoutes();
        tx().execute(status -> cancelOrder.cancel(routes.cancelledOrderId(), Instant.now()));

        tx().execute(status ->
                reassign.reassign(routes.fromRouteId(), routes.movedOrderId(), routes.toRouteId()));

        List<Integer> seqs = seqsOf(routes.fromRouteId());
        assertThat(seqs).isEqualTo(
                java.util.stream.IntStream.rangeClosed(1, seqs.size()).boxed().toList());
        assertThat(stopStatusOf(routes.cancelledOrderId()))
                .as("재배정이 취소를 되살리지 않는다").isEqualTo("CANCELLED");
    }

    @Test
    void 배송이_끝난_stop_의_취소는_거부하고_아무것도_바꾸지_않는다() {
        Planned planned = plannedRoute();
        UUID target = planned.lastStopOrderId();
        // route_stops.status 를 ARRIVED 로 옮기는 코드는 아직 없다 — §4.1 에서 delivery.status 의
        // 소비자가 dispatch 가 아니기 때문이고, 그 사실은 ADR-026 후속 정정에 적었다. 그래서
        // 이 분기를 시험하려면 여기서 손으로 옮긴다.
        tx().executeWithoutResult(status -> entityManager.createNativeQuery("""
                UPDATE route_stops SET status = 'COMPLETED' WHERE id = (
                       SELECT stop_id FROM route_stop_orders WHERE order_id = ?)
                """).setParameter(1, target).executeUpdate());
        int revision = revisionOf(planned.routeId());

        CancelOrderUseCase.Outcome outcome =
                tx().execute(status -> cancelOrder.cancel(target, Instant.now()));

        assertThat(outcome).isEqualTo(CancelOrderUseCase.Outcome.TOO_LATE);
        assertThat(tx().execute(status -> candidates.findById(target)).orElseThrow().status().name())
                .as("물건이 전달된 뒤에 상태를 바꾸면 DB 가 사실과 어긋난다").isEqualTo("PLANNED");
        assertThat(revisionOf(planned.routeId())).isEqualTo(revision);
        assertThat(outboxCount()).isZero();
    }

    // ---------------------------------------------------------------- 도우미

    /** 계획된 라우트 하나와 그 마지막 stop 의 주문. */
    private record Planned(UUID routeId, UUID lastStopOrderId, int distanceM) {
    }

    private Planned plannedRoute() {
        UUID waveId = Ids.newId();
        seedCandidates(waveId, 8);
        runPlan.run(RunPlanCommand.of(waveId, CAMP_ID, CAMP));
        PlanView plan = tx().execute(status -> planQueries.findPlanByWave(waveId)).orElseThrow();
        UUID routeId = plan.routes().getFirst().routeId();
        RouteView route = tx().execute(status -> planQueries.findRoute(routeId)).orElseThrow();
        // 계획이 이미 route.assigned 를 넣어 두었다. 개정만 보려고 지운다.
        tx().executeWithoutResult(status ->
                entityManager.createNativeQuery("DELETE FROM outbox_events").executeUpdate());
        return new Planned(routeId, route.stops().getLast().orderIds().getFirst(),
                distanceOf(routeId));
    }

    /** 두 라우트 — 하나에서는 주문을 취소하고, 다른 주문을 옮긴다. */
    private record TwoRoutes(UUID fromRouteId, UUID toRouteId, UUID cancelledOrderId,
            UUID movedOrderId) {
    }

    private TwoRoutes twoRoutes() {
        UUID waveId = Ids.newId();
        seedCandidates(waveId, 40);
        runPlan.run(RunPlanCommand.of(waveId, CAMP_ID, CAMP));
        PlanView plan = tx().execute(status -> planQueries.findPlanByWave(waveId)).orElseThrow();
        assertThat(plan.routes()).as("재배정을 보려면 라우트가 둘 이상이어야 한다").hasSizeGreaterThan(1);

        // 가장 많이 실은 쪽에서 가장 적게 실은 쪽으로 옮긴다 — 아무 둘이나 고르면 목적지가 꽉 차
        // 하드 룰 위반(409)이 나고, 그것은 이 테스트가 보려는 것이 아니다.
        List<PlanView.RouteSummary> byLoad = plan.routes().stream()
                .sorted(Comparator.comparingInt(PlanView.RouteSummary::stopCount)).toList();
        UUID fromRouteId = byLoad.getLast().routeId();
        RouteView from = tx().execute(status -> planQueries.findRoute(fromRouteId)).orElseThrow();
        return new TwoRoutes(fromRouteId, byLoad.getFirst().routeId(),
                from.stops().getLast().orderIds().getFirst(),
                from.stops().getFirst().orderIds().getFirst());
    }

    /** 순번 순서대로, stop 마다 주문 하나씩. */
    @SuppressWarnings("unchecked")
    private List<UUID> orderIdsBySeq(UUID routeId) {
        return tx().execute(status -> (List<UUID>) entityManager.createNativeQuery("""
                SELECT DISTINCT ON (s.seq) o.order_id FROM route_stops s
                  JOIN route_stop_orders o ON o.stop_id = s.id
                 WHERE s.route_id = ?
                 ORDER BY s.seq, o.order_id
                """).setParameter(1, routeId).getResultList());
    }

    @SuppressWarnings("unchecked")
    private List<Integer> seqsOf(UUID routeId) {
        List<Number> rows = tx().execute(status -> (List<Number>) entityManager
                .createNativeQuery("SELECT seq FROM route_stops WHERE route_id = ? ORDER BY seq")
                .setParameter(1, routeId).getResultList());
        return rows.stream().map(Number::intValue).toList();
    }

    private String stopStatusOf(UUID orderId) {
        return tx().execute(status -> (String) entityManager.createNativeQuery("""
                SELECT s.status FROM route_stops s
                  JOIN route_stop_orders o ON o.stop_id = s.id
                 WHERE o.order_id = ?
                """).setParameter(1, orderId).getSingleResult());
    }

    private Instant arrivalOfLastStop(UUID routeId) {
        return tx().execute(status -> (Instant) entityManager.createNativeQuery("""
                SELECT planned_arrival FROM route_stops
                 WHERE route_id = ? ORDER BY seq DESC LIMIT 1
                """).setParameter(1, routeId).getSingleResult());
    }

    private int revisionOf(UUID routeId) {
        return ((Number) tx().execute(status -> entityManager
                .createNativeQuery("SELECT revision FROM routes WHERE id = ?")
                .setParameter(1, routeId).getSingleResult())).intValue();
    }

    private int distanceOf(UUID routeId) {
        return ((Number) tx().execute(status -> entityManager
                .createNativeQuery("SELECT distance_m FROM routes WHERE id = ?")
                .setParameter(1, routeId).getSingleResult())).intValue();
    }

    private long outboxCount() {
        return ((Number) tx().execute(status -> entityManager
                .createNativeQuery("SELECT count(*) FROM outbox_events").getSingleResult()))
                .longValue();
    }

    private String lastRouteAssigned(UUID routeId) {
        return tx().execute(status -> (String) entityManager.createNativeQuery("""
                SELECT payload::text FROM outbox_events
                 WHERE topic = 'dawnline.route.assigned.v1' AND aggregate_id = ?
                 ORDER BY id DESC LIMIT 1
                """).setParameter(1, routeId).getSingleResult());
    }

    private List<UUID> seedCandidates(UUID waveId, int count) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        TimeWindow window = new TimeWindow(now.plus(Duration.ofHours(1)),
                now.plus(Duration.ofHours(5)));
        List<UUID> orderIds = new ArrayList<>(count);
        tx().executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                UUID orderId = Ids.newId();
                orderIds.add(orderId);
                candidates.insertIfAbsent(DispatchCandidate.load(orderId, waveId, CAMP_ID, null,
                        GeoPoint.of(CAMP.lat() + 0.004d * (i % 8 + 1),
                                CAMP.lng() + 0.005d * (i / 8 + 1)),
                        40_000, 80_000, false, false, window, 60, 0, now));
            }
        });
        return orderIds;
    }
}
