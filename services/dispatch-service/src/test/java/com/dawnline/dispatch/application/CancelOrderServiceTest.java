package com.dawnline.dispatch.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.port.in.CancelOrderUseCase;
import com.dawnline.dispatch.application.port.out.RouteSnapshot;
import com.dawnline.dispatch.application.port.out.VehicleCatalog;
import com.dawnline.dispatch.domain.CandidateStatus;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.dispatch.domain.PlanMode;
import com.dawnline.dispatch.domain.RoutePlan;
import com.dawnline.dispatch.domain.optimizer.HaversineDistance;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 취소 처리 (DESIGN.md §6.10, ADR-026).
 *
 * <p>§6.10 의 표가 네 행이므로 여기에도 네 분기가 있어야 한다. 표를 코드로 옮겨 놓고 그중 셋만
 * 시험하면 남은 하나는 "적혀 있지만 돌지 않는 분기" 가 된다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("CancelOrderServiceTest — §6.10 네 분기")
class CancelOrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-06T01:00:00Z");
    private static final Instant CANCELLED_AT = NOW.plus(Duration.ofMinutes(20));
    private static final UUID CAMP_ID = Ids.newId();
    private static final TimeWindow WINDOW = new TimeWindow(NOW, NOW.plus(Duration.ofHours(8)));
    private static final GeoPoint NEAR = GeoPoint.of(37.5700, 126.9779);
    private static final GeoPoint MID = GeoPoint.of(37.5800, 126.9779);
    private static final GeoPoint FAR = GeoPoint.of(37.5900, 126.9779);

    private final InMemoryDispatchPorts.Candidates candidates =
            new InMemoryDispatchPorts.Candidates();
    private final InMemoryDispatchPorts.Plans plans = new InMemoryDispatchPorts.Plans();
    private final InMemoryDispatchPorts.Events events = new InMemoryDispatchPorts.Events();
    private final InMemoryDispatchPorts.CancellableRoutes routes =
            new InMemoryDispatchPorts.CancellableRoutes(candidates);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DispatchMetrics metrics = new DispatchMetrics(registry);

    /** 차량은 <strong>한 번만</strong> 만든다 — fleet() 은 부를 때마다 새 id 를 만든다. */
    private final VehicleCatalog fleet = InMemoryDispatchPorts.fleet(1, NOW);

    private final CancelOrderService service = new CancelOrderService(candidates, routes, plans,
            fleet, InMemoryDispatchPorts.rules(RuleSet.empty()),
            events, new HaversineDistance(1.3d, 25.0d), metrics);

    // ------------------------------------------------------------ 표에 없는 입력

    @Test
    void 후보가_아닌_주문의_취소는_아무것도_바꾸지_않는다() {
        // fulfillment 가 배차 불가로 끝냈거나 아직 fulfillment.planned 가 오지 않았다.
        CancelOrderUseCase.Outcome outcome = service.cancel(Ids.newId(), CANCELLED_AT);

        assertThat(outcome).isEqualTo(CancelOrderUseCase.Outcome.NOT_A_CANDIDATE);
        assertThat(events.revised).isEmpty();
    }

    @Test
    void 이미_취소된_주문은_다시_발행하지_않는다() {
        // at-least-once 재전달의 정상 결과다. 여기서 다시 발행하면 revision 이 근거 없이 오른다.
        UUID orderId = candidate(NOW);
        service.cancel(orderId, CANCELLED_AT);
        int published = events.revised.size();

        CancelOrderUseCase.Outcome outcome = service.cancel(orderId, CANCELLED_AT);

        assertThat(outcome).isEqualTo(CancelOrderUseCase.Outcome.ALREADY_CANCELLED);
        assertThat(events.revised).hasSize(published);
    }

    // ------------------------------------------------------------ 첫째·둘째 행

    @Test
    void 계획_전_취소는_후보만_CANCELLED_로_두고_이벤트가_없다() {
        UUID orderId = candidate(NOW);

        CancelOrderUseCase.Outcome outcome = service.cancel(orderId, CANCELLED_AT);

        assertThat(outcome).isEqualTo(CancelOrderUseCase.Outcome.CANDIDATE_CANCELLED);
        assertThat(candidates.findById(orderId).orElseThrow().status())
                .isEqualTo(CandidateStatus.CANCELLED);
        assertThat(events.revised).as("아직 아무도 이 주문을 모른다").isEmpty();
    }

    @Test
    void 취소된_후보는_지워지지_않는다() {
        // "주문 X 는 왜 라우트에 없나" 에 답할 수 있어야 한다 (§6.3 설명 가능성, ADR-026).
        UUID orderId = candidate(NOW);

        service.cancel(orderId, CANCELLED_AT);

        assertThat(candidates.findById(orderId)).isPresent();
        assertThat(candidates.findById(orderId).orElseThrow().updatedAt()).isEqualTo(CANCELLED_AT);
    }

    // ------------------------------------------------------------ 셋째 행

    @Test
    void 발행된_라우트의_취소는_stop_을_죽이고_개정을_올린다() {
        Published published = publishedRoute();

        CancelOrderUseCase.Outcome outcome = service.cancel(published.middleOrderId(), CANCELLED_AT);

        assertThat(outcome).isEqualTo(CancelOrderUseCase.Outcome.ROUTE_REVISED);
        assertThat(events.revisions).containsExactly(2);
        RouteSnapshot snapshot = events.revised.getFirst();
        assertThat(snapshot.stops()).hasSize(3);
        assertThat(snapshot.stops().get(1).cancelled()).isTrue();
        assertThat(snapshot.stops().get(1).cancelledOrderIds())
                .containsExactly(published.middleOrderId());
    }

    @Test
    void 취소된_stop_을_페이로드에서_지우지_않고_순번도_그대로_둔다() {
        // 부재는 값이 아니다 — 지우면 소비자가 "취소" 와 "다른 라우트로 이동" 과 "발행 누락" 을
        // 구별할 수 없다. 순번을 다시 매기면 기사가 보던 번호가 바뀐다.
        Published published = publishedRoute();

        service.cancel(published.middleOrderId(), CANCELLED_AT);

        assertThat(events.revised.getFirst().stops())
                .extracting(RouteSnapshot.StopSnapshot::seq).containsExactly(1, 2, 3);
    }

    @Test
    void 취소는_이후_stop_의_도착을_당긴다() {
        // 순서는 그대로 두고 시간만 재전파한다 (ADR-026 결정 1).
        Published published = publishedRoute();
        Instant before = routes.row(published.routeId(), 3).arrival;

        service.cancel(published.middleOrderId(), CANCELLED_AT);

        assertThat(routes.row(published.routeId(), 3).arrival).isBefore(before);
    }

    @Test
    void 통합된_stop_은_일부만_취소되면_여전히_방문한다() {
        // StopMerger 가 같은 지점·같은 약속창의 주문을 묶는다 (§6.5 1단계). 남은 주문을
        // 배송해야 하므로 stop 은 살아 있고, 어느 주문이 죽었는지는 cancelledOrderIds 가 말한다
        // (ADR-026 [후속 정정 — Phase 3-6]).
        Published published = publishedRoute();
        UUID kept = published.mergedOrderIds().getFirst();
        UUID cancelled = published.mergedOrderIds().getLast();

        service.cancel(cancelled, CANCELLED_AT);

        RouteSnapshot.StopSnapshot merged = events.revised.getFirst().stops().getLast();
        assertThat(merged.cancelled()).as("남은 주문이 있으면 stop 은 죽지 않는다").isFalse();
        assertThat(merged.orderIds()).containsExactly(kept, cancelled);
        assertThat(merged.cancelledOrderIds()).containsExactly(cancelled);
    }

    @Test
    void 마지막_주문이_취소되면_라우트는_아무_데도_가지_않는다() {
        Published published = publishedRoute();

        service.cancel(published.firstOrderId(), CANCELLED_AT);
        service.cancel(published.middleOrderId(), CANCELLED_AT);
        published.mergedOrderIds().forEach(orderId -> service.cancel(orderId, CANCELLED_AT));

        assertThat(routes.retimed.get(published.routeId()))
                .as("살아 있는 stop 이 없으면 다시 계산할 경로가 없다").isNull();
        RouteSnapshot last = events.revised.getLast();
        assertThat(last.distanceM()).isZero();
        assertThat(last.costKrw()).isZero();
        assertThat(last.stops()).as("죽은 라우트도 stop 을 지우지 않는다").hasSize(3)
                .allSatisfy(stop -> assertThat(stop.cancelled()).isTrue());
    }

    // ------------------------------------------------------------ 넷째 행

    @Test
    void 배송이_끝난_stop_의_취소는_거부하고_센다() {
        Published published = publishedRoute();
        routes.row(published.routeId(), 2).status = "COMPLETED";

        CancelOrderUseCase.Outcome outcome = service.cancel(published.middleOrderId(), CANCELLED_AT);

        assertThat(outcome).isEqualTo(CancelOrderUseCase.Outcome.TOO_LATE);
        // 상태 불변 — 물건이 전달된 뒤에 CANCELLED 로 바꾸면 DB 가 사실과 어긋난다.
        assertThat(candidates.findById(published.middleOrderId()).orElseThrow().status())
                .isEqualTo(CandidateStatus.PENDING);
        assertThat(events.revised).isEmpty();
        assertThat(registry.counter(DispatchMetrics.CANCEL_TOO_LATE, "camp", CAMP_ID.toString())
                .count()).isEqualTo(1.0d);
    }

    @Test
    void 도착만_한_stop_도_거부한다() {
        // ARRIVED 는 기사가 그 지점에 닿았다는 뜻이다. 경계는 "출발했는가" 가 아니라 "닿았는가" 다.
        Published published = publishedRoute();
        routes.row(published.routeId(), 2).status = "ARRIVED";

        assertThat(service.cancel(published.middleOrderId(), CANCELLED_AT))
                .isEqualTo(CancelOrderUseCase.Outcome.TOO_LATE);
    }

    @Test
    void 아직_닿지_않은_stop_은_출발_여부와_무관하게_받는다() {
        // 미출발과 출발 후 미도착은 처리가 같다 — 둘 다 "건너뛴다" 다 (ADR-026 결정 2).
        Published published = publishedRoute();
        routes.row(published.routeId(), 2).status = "PLANNED";

        assertThat(service.cancel(published.middleOrderId(), CANCELLED_AT))
                .isEqualTo(CancelOrderUseCase.Outcome.ROUTE_REVISED);
    }

    // ------------------------------------------------------------ 도우미

    /** 라우트 하나와 그 위의 주문들. 세 번째 stop 은 주문 둘이 통합된 지점이다. */
    private record Published(UUID routeId, UUID firstOrderId, UUID middleOrderId,
            List<UUID> mergedOrderIds) {
    }

    private Published publishedRoute() {
        RoutePlan plan = plan();
        UUID vehicleId = fleet.availableAt(CAMP_ID, NOW).getFirst().id().value();

        UUID first = candidate(NOW);
        UUID middle = candidate(NOW);
        List<UUID> merged = List.of(candidate(NOW), candidate(NOW));

        List<InMemoryDispatchPorts.CancellableRoutes.StopRow> stops = new ArrayList<>();
        stops.add(new InMemoryDispatchPorts.CancellableRoutes.StopRow(
                1, NEAR, 60, NOW.plus(Duration.ofMinutes(10)), List.of(first)));
        stops.add(new InMemoryDispatchPorts.CancellableRoutes.StopRow(
                2, MID, 60, NOW.plus(Duration.ofMinutes(30)), List.of(middle)));
        stops.add(new InMemoryDispatchPorts.CancellableRoutes.StopRow(
                3, FAR, 120, NOW.plus(Duration.ofMinutes(50)), merged));
        UUID routeId = routes.route(plan.id(), vehicleId, stops);
        return new Published(routeId, first, middle, merged);
    }

    private RoutePlan plan() {
        RoutePlan plan = RoutePlan.request(Ids.newId(), Ids.newId(), CAMP_ID,
                InMemoryDispatchPorts.CAMP);
        plans.insertIfAbsent(plan);
        plan.begin("baseline-nn", PlanMode.FULL, 1L, 1, NOW);
        plans.update(plan);
        return plan;
    }

    private UUID candidate(Instant at) {
        UUID orderId = Ids.newId();
        candidates.put(DispatchCandidate.load(orderId, Ids.newId(), CAMP_ID, null, NEAR,
                10_000, 20_000, false, false, WINDOW, 60, 0, at));
        return orderId;
    }
}
