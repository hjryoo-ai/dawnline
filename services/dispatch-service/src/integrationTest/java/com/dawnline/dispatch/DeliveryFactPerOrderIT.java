package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.port.in.PlanView;
import com.dawnline.dispatch.application.port.in.ReassignStopUseCase;
import com.dawnline.dispatch.application.port.in.RecordDeliveryStatusUseCase;
import com.dawnline.dispatch.application.port.in.RecordDeliveryStatusUseCase.DeliveryStatusCommand;
import com.dawnline.dispatch.application.port.in.RouteView;
import com.dawnline.dispatch.application.port.in.RunPlanCommand;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.application.port.out.PlanQueries;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.dispatch.domain.RouteStopStatus;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 배송의 사실은 주문에 귀속된다 — stop 의 상태는 그 주문들에서 나온다 (ADR-047 재검토 지점 ④, 7-4 결정 E).
 *
 * <p>{@code route_stops.status} 는 그 stop 의 <em>어느</em> 주문의 {@code delivery.status} 가 와도 stop 전체를 덮는다. 한 stop 에 주문이
 * 여럿이면 — 계획이 같은 지점의 주문을 합쳤거나(§6.5 1단계) 재배정이 같은 지점의 stop 에 붙였거나(§5.3) — 그 하나가 나머지의 사실을
 * 대신 말한다. 여기 셋은 그 모양이다.
 */
@SpringBootTest(classes = DispatchApplication.class)
@Import(PlanningClock.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("DeliveryFactPerOrderIT — 사실은 주문에, stop 은 그 주문들에서")
class DeliveryFactPerOrderIT extends DispatchIntegrationTestBase {

    /** 시드의 첫 캠프 (서울 북부) — {@code ReassignRaceIT} 와 같다. */
    private static final UUID CAMP_ID = UUID.fromString("01a06edd-6c00-7000-8001-000000000001");
    private static final GeoPoint CAMP = GeoPoint.of(37.640000, 127.030000);

    @Autowired
    private RunPlanUseCase runPlan;

    @Autowired
    private ReassignStopUseCase reassign;

    @Autowired
    private RecordDeliveryStatusUseCase deliveryStatus;

    @Autowired
    private PlanQueries planQueries;

    @Autowired
    private DispatchCandidateRepository candidates;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * 릴레이를 끈다 — 발행을 보지 않는다.
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void relayOff(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    @BeforeEach
    void clean() {
        tx().executeWithoutResult(status -> {
            for (String table : new String[] {"plan_explanations", "route_stop_orders", "route_stops", "routes",
                    "route_plans", "dispatch_candidates", "outbox_events", "processed_events"}) {
                entityManager.createNativeQuery("DELETE FROM " + table).executeUpdate();
            }
        });
    }

    @Test
    void 한_stop_의_주문이_하나만_끝나면_stop_은_끝나지_않는다() {
        // 같은 지점의 두 주문 중 하나는 받고 하나는 부재 — 기사는 둘을 따로 찍고 tracking 은 두 건을 낸다.
        RouteView route = plan().from();
        RouteView.StopView merged = mergedStopOf(route);
        UUID delivered = merged.orderIds().getFirst();

        report(route.routeId(), merged.seq(), List.of(delivered), RouteStopStatus.COMPLETED);

        assertThat(statusOfStopHolding(merged.orderIds().getLast()))
                .as("다른 주문의 완료가 이 주문을 끝내지 않는다").isNotEqualTo("COMPLETED");
        assertThat(statusOfStopHolding(delivered)).as("일부만 끝났다 — 진행 중").isEqualTo("ARRIVED");
        assertThat(orderStatus(delivered)).isEqualTo("COMPLETED");
        assertThat(orderStatus(merged.orderIds().getLast())).isEqualTo("PLANNED");
        assertThat(firstTouchOfStop(merged.stopId())).as("stop 에 처음 닿은 시각은 그 주문의 것").isEqualTo(orderFirstTouch(delivered));
    }

    @Test
    void 옮겨_온_주문이_있는_stop_에서_원래_주문만_끝나면_옮겨_온_주문은_남는다() {
        // 재배정이 같은 건물의 PLANNED stop 에 붙였다. 그 라우트의 기사는 옛 개정으로 원래 주문만 찍는다.
        Plan plan = plan();
        RouteView.StopView merged = mergedStopOf(plan.from());
        UUID moved = merged.orderIds().getFirst();
        RouteView.StopView target = plan.to().stops().getFirst();
        moveTo(target.stopId(), merged);
        reassign.reassign(plan.from().routeId(), moved, plan.to().routeId());
        assertThat(stopHolding(moved)).as("전제 — 같은 지점의 stop 에 붙었다").isEqualTo(target.stopId());

        report(plan.to().routeId(), target.seq(), target.orderIds(), RouteStopStatus.COMPLETED);

        assertThat(statusOfStopHolding(moved))
                .as("배송되지 않은 주문이 배송된 것으로 보이면 계획에서 사라진다 — 재계획도, 보존도, 차량 비활성화도 그 stop 을 끝난 것으로 본다")
                .isNotEqualTo("COMPLETED");
        assertThat(orderStatus(moved)).isEqualTo("PLANNED");
        assertThat(unfinishedStops(plan.to().routeId())).as("받은 라우트는 끝나지 않았다 — 비활성화 409 가 말한다").isPositive();
    }

    @Test
    void 가르는_동안_함께_배송된_주문은_옮겨_간_자리에도_적힌다() {
        // 합쳐진 stop 에서 하나를 다른 라우트로 뗐다. 옛 개정을 든 기사는 그 건물의 두 주문을 함께 전하고 둘을 한 번에 찍는다.
        // tracking 은 둘 다 COMPLETED 다 — dispatch 도 둘 다 끝나야 V8 이 맞는다(ADR-068 후속 C 「가르는 동안 도착한 배송」).
        Plan plan = plan();
        RouteView.StopView merged = mergedStopOf(plan.from());
        UUID kept = merged.orderIds().getFirst();
        UUID moved = merged.orderIds().getLast();
        reassign.reassign(plan.from().routeId(), moved, plan.to().routeId());
        assertThat(routeOf(moved)).as("전제 — 다른 라우트로 갔다").isEqualTo(plan.to().routeId());

        report(plan.from().routeId(), merged.seq(), List.of(kept, moved), RouteStopStatus.COMPLETED);

        assertThat(statusOfStopHolding(kept)).isEqualTo("COMPLETED");
        assertThat(statusOfStopHolding(moved)).as("사실은 주문의 것이다 — 그 주문이 지금 있는 자리에 적힌다").isEqualTo("COMPLETED");
        assertThat(orderStatus(moved)).isEqualTo("COMPLETED");
    }

    // ---------------------------------------------------------------- 도우미

    /** 가장 많이 실은 라우트(from), 가장 적게 실은 라우트(to). */
    private record Plan(RouteView from, RouteView to) {
    }

    private void report(UUID routeId, int seq, List<UUID> orderIds, RouteStopStatus status) {
        tx().executeWithoutResult(tx -> deliveryStatus.record(
                new DeliveryStatusCommand(routeId, seq, orderIds, status, PlanningClock.PLAN_AT.plus(Duration.ofHours(2)))));
    }

    private Plan plan() {
        UUID waveId = Ids.newId();
        seedCandidates(waveId, 120);
        runPlan.run(RunPlanCommand.of(waveId, CAMP_ID, CAMP, null));
        PlanView plan = tx().execute(status -> planQueries.findPlanByWave(waveId)).orElseThrow();
        assertThat(plan.routes()).as("라우트 둘 이상 — 한 곳에서 다른 곳으로 옮긴다").hasSizeGreaterThanOrEqualTo(2);
        List<RouteView> byLoad = plan.routes().stream()
                .sorted(Comparator.comparingInt(PlanView.RouteSummary::stopCount).reversed())
                .map(route -> tx().execute(status -> planQueries.findRoute(route.routeId())).orElseThrow())
                .toList();
        return new Plan(byLoad.getFirst(), byLoad.getLast());
    }

    private static RouteView.StopView mergedStopOf(RouteView route) {
        return route.stops().stream().filter(stop -> stop.orderIds().size() > 1).findFirst()
                .orElseThrow(() -> new IllegalStateException("전제 — 합쳐진 stop 이 있다"));
    }

    /** 받을 라우트의 stop 하나를 옮길 stop 과 같은 지점에 둔다 — 같은 건물을 두 라우트가 도는 날. */
    private void moveTo(UUID stopId, RouteView.StopView at) {
        tx().executeWithoutResult(status -> entityManager.createNativeQuery("""
                UPDATE route_stops SET lat = (SELECT lat FROM route_stops WHERE id = ?),
                                       lng = (SELECT lng FROM route_stops WHERE id = ?)
                 WHERE id = ?
                """).setParameter(1, at.stopId()).setParameter(2, at.stopId()).setParameter(3, stopId).executeUpdate());
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private UUID routeOf(UUID orderId) {
        return tx().execute(status -> (UUID) entityManager.createNativeQuery("""
                SELECT s.route_id FROM route_stops s JOIN route_stop_orders o ON o.stop_id = s.id WHERE o.order_id = ?
                """).setParameter(1, orderId).getSingleResult());
    }

    private UUID stopHolding(UUID orderId) {
        return tx().execute(status -> (UUID) entityManager.createNativeQuery(
                "SELECT stop_id FROM route_stop_orders WHERE order_id = ?").setParameter(1, orderId).getSingleResult());
    }

    private String orderStatus(UUID orderId) {
        return tx().execute(status -> (String) entityManager.createNativeQuery(
                "SELECT status FROM route_stop_orders WHERE order_id = ?").setParameter(1, orderId).getSingleResult());
    }

    private Instant orderFirstTouch(UUID orderId) {
        return tx().execute(status -> (Instant) entityManager.createNativeQuery(
                "SELECT actual_at FROM route_stop_orders WHERE order_id = ?").setParameter(1, orderId).getSingleResult());
    }

    private Instant firstTouchOfStop(UUID stopId) {
        return tx().execute(status -> (Instant) entityManager.createNativeQuery(
                "SELECT actual_at FROM route_stops WHERE id = ?").setParameter(1, stopId).getSingleResult());
    }

    /** 끝나지 않은 stop 의 수 — 보존 · 비활성화 · 재계획이 쓰는 같은 조각이다({@code JdbcDispatchRetention.UNFINISHED_STOP}). */
    private long unfinishedStops(UUID routeId) {
        return tx().execute(status -> ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM route_stops s WHERE s.route_id = ? AND "
                        + com.dawnline.dispatch.adapter.out.persistence.JdbcDispatchRetention.UNFINISHED_STOP)
                .setParameter(1, routeId).getSingleResult()).longValue());
    }

    private String statusOfStopHolding(UUID orderId) {
        return tx().execute(status -> (String) entityManager.createNativeQuery("""
                SELECT s.status FROM route_stops s JOIN route_stop_orders o ON o.stop_id = s.id WHERE o.order_id = ?
                """).setParameter(1, orderId).getSingleResult());
    }

    /** 지점마다 주문 둘 — 계획이 한 stop 으로 합친다({@code ReassignRaceIT} 의 픽스처와 같다). */
    private void seedCandidates(UUID waveId, int count) {
        Instant now = PlanningClock.PLAN_AT.truncatedTo(ChronoUnit.MICROS);
        TimeWindow window = new TimeWindow(now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(5)));
        tx().executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                int point = i / 2;
                candidates.insertIfAbsent(DispatchCandidate.load(Ids.newId(), waveId, CAMP_ID, null,
                        GeoPoint.of(CAMP.lat() + 0.004d * (point % 8 + 1) + 0.00002d * (i % 2),
                                CAMP.lng() + 0.005d * (point / 8 + 1)),
                        20_000, 40_000, false, false, window, 60, false, 0, now));
            }
        });
    }
}
