package com.dawnline.dispatch.application;

import com.dawnline.common.error.ConflictException;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.dispatch.application.port.in.CancelOrderUseCase;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.application.port.out.DispatchEvents;
import com.dawnline.dispatch.application.port.out.RouteMutations;
import com.dawnline.dispatch.application.port.out.RoutePlanRepository;
import com.dawnline.dispatch.application.port.out.RouteSnapshot;
import com.dawnline.dispatch.application.port.out.RuleCatalog;
import com.dawnline.dispatch.application.port.out.VehicleCatalog;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.dispatch.domain.RoutePlan;
import com.dawnline.dispatch.domain.optimizer.CampDepot;
import com.dawnline.dispatch.domain.optimizer.CostModel;
import com.dawnline.dispatch.domain.optimizer.DistanceProvider;
import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import com.dawnline.dispatch.domain.optimizer.RouteAccumulator;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code order.cancelled} 를 반영한다 (DESIGN.md §6.10, ADR-026).
 *
 * <h2>분기는 라우트가 아니라 stop 의 상태로 자른다</h2>
 * "출발했는가" 는 라우트의 사실이고 취소가 영향을 주는 것은 stop 이다. 미출발과 출발 후 미도착은
 * 처리가 같아서(건너뛴다) 그 구분이 아무것도 만들지 않는다. 진짜 경계는 <strong>기사가 그 지점에
 * 닿았는가</strong> 하나다.
 *
 * <h2>다시 풀지 않는다</h2>
 * stop 을 죽이고 이후 stop 의 시각만 앞으로 당긴다. 순서는 그대로다 — 기사가 이미 그 순서를 보고
 * 있고, 취소는 시간을 <em>벌어 주는</em> 사건이라 재계획이 필요한 방향의 반대다. 하드 룰도 다시
 * 돌리지 않는다: 짐이 줄어드는 변경은 룰을 어길 수 없고, 어차피 취소는 위쪽에서 이미 일어난
 * 사실이라 여기서 거부해도 되돌릴 것이 없다. (운영자 재배정은 다르다 — 그쪽은 사람이 만드는
 * 변경이라 어길 수 있고, 그래서 {@link ReassignStopService} 가 다시 검사한다.)
 */
public class CancelOrderService implements CancelOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(CancelOrderService.class);

    private final DispatchCandidateRepository candidates;
    private final RouteMutations routes;
    private final RoutePlanRepository plans;
    private final VehicleCatalog vehicles;
    private final RuleCatalog rules;
    private final DispatchEvents events;
    private final DistanceProvider distance;
    private final DispatchMetrics metrics;
    private final CostModel cost = new CostModel();

    /**
     * @param candidates 후보 저장소
     * @param routes     라우트 조작
     * @param plans      계획 저장소 (캠프 좌표가 여기 있다)
     * @param vehicles   차량 카탈로그
     * @param rules      룰 카탈로그
     * @param events     발행
     * @param distance   거리 제공자
     * @param metrics    §9.1 메트릭
     */
    public CancelOrderService(DispatchCandidateRepository candidates, RouteMutations routes,
            RoutePlanRepository plans, VehicleCatalog vehicles, RuleCatalog rules,
            DispatchEvents events, DistanceProvider distance, DispatchMetrics metrics) {

        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.vehicles = Objects.requireNonNull(vehicles, "vehicles");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.events = Objects.requireNonNull(events, "events");
        this.distance = Objects.requireNonNull(distance, "distance");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    @Transactional
    public Outcome cancel(UUID orderId, Instant cancelledAt) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(cancelledAt, "cancelledAt");

        Optional<DispatchCandidate> found = candidates.findById(orderId);
        if (found.isEmpty()) {
            // 우리 후보가 아니다. fulfillment 가 배차 불가로 끝냈거나 아직 fulfillment.planned 가
            // 오지 않았다. 후자라면 그 이벤트가 왔을 때 이미 취소된 주문을 적재하게 되는데,
            // 그것은 §6.10 이 아니라 순서 역전의 문제라 여기서 만들어 두지 않는다.
            log.debug("후보가 아닌 주문의 취소입니다: orderId={}", orderId);
            return Outcome.NOT_A_CANDIDATE;
        }
        DispatchCandidate candidate = found.get();

        Optional<RouteMutations.AssignedStop> assigned = routes.findAssignedStop(orderId);
        if (assigned.isPresent() && assigned.get().visited()) {
            // 물건이 이미 전달됐다. 상태를 CANCELLED 로 바꾸면 DB 가 사실과 어긋난다.
            // 막는 것이 아니라 보이게 하는 것이 이 분기의 역할이다 (§9.4 알림).
            metrics.cancelTooLate(candidate.campId());
            log.warn("배송이 끝난 뒤 도착한 취소입니다. 상태를 바꾸지 않습니다: orderId={} routeId={} stop={}",
                    orderId, assigned.get().routeId(), assigned.get().status());
            return Outcome.TOO_LATE;
        }

        if (!candidate.cancel(cancelledAt)) {
            return Outcome.ALREADY_CANCELLED;
        }
        candidates.update(candidate);

        if (assigned.isEmpty()) {
            // 아직 라우트가 없다. 계획 전이면 다음 계획이 이 후보를 집지 않고, 계획 중이면
            // 발행 직전 재검증이 stop 에서 뺀다 — revision 을 쓰지 않고 닫는 자리다.
            log.info("계획 전 취소입니다: orderId={}", orderId);
            return Outcome.CANDIDATE_CANCELLED;
        }
        revise(assigned.get(), orderId);
        return Outcome.ROUTE_REVISED;
    }

    /** 발행된 라우트에서 뺀다 — stop 표시, 시간 재전파, 개정 발행 (§6.10 셋째 행). */
    private void revise(RouteMutations.AssignedStop assigned, UUID orderId) {
        UUID routeId = assigned.routeId();
        boolean stopDied = routes.cancelStopIfAllOrdersCancelled(assigned.stopId());

        RouteMutations.RouteHeader header = routes.findHeader(routeId)
                .orElseThrow(() -> NotFoundException.of("Route", routeId.toString()));
        RoutePlan plan = plans.findById(header.planId())
                .orElseThrow(() -> NotFoundException.of("RoutePlan", header.planId().toString()));

        routes.retime(routeId, retimed(plan, header, routeId));
        int revision = routes.bumpRevision(routeId);
        RouteSnapshot snapshot = routes.snapshot(routeId)
                .orElseThrow(() -> NotFoundException.of("Route", routeId.toString()));
        events.routeRevised(plan, snapshot, revision);

        log.info("발행된 라우트에서 취소를 반영했습니다: orderId={} routeId={} stop취소={} revision={}",
                orderId, routeId, stopDied, revision);
    }

    /**
     * 살아 있는 stop 만으로 시각을 다시 계산한다. 하나도 남지 않았으면 {@code null} —
     * 라우트는 남지만 아무 데도 가지 않는다.
     */
    private @Nullable PlannedRoute retimed(RoutePlan plan, RouteMutations.RouteHeader header,
            UUID routeId) {

        List<Stop> live = routes.loadStops(routeId);
        if (live.isEmpty()) {
            return null;
        }
        Instant startAt = plan.startedAt().orElseThrow(() -> new ConflictException(
                "시작 시각이 없는 계획의 라우트는 다시 쓸 수 없습니다",
                Map.of("planId", plan.id().toString())));
        CampDepot depot = new CampDepot(plan.campId(), plan.depot().orElseThrow(
                () -> new ConflictException("캠프 좌표가 없는 계획은 다시 쓸 수 없습니다",
                        Map.of("planId", plan.id().toString()))));
        VehicleSpec vehicle = vehicleOf(plan.campId(), startAt, header.vehicleId(), routeId);

        RouteAccumulator route =
                new RouteAccumulator(rules.forCamp(plan.campId()), vehicle, depot, distance, startAt);
        // check() 를 부르지 않는다. 짐이 줄어드는 변경이라 하드 룰을 어길 수 없고, 어겼다 해도
        // 취소를 되돌릴 방법이 없다 — 여기서 거부하면 라우트가 옛 시각을 든 채 남는다.
        live.forEach(route::append);
        return route.toRoute(cost);
    }

    private VehicleSpec vehicleOf(UUID campId, Instant startAt, UUID vehicleId, UUID routeId) {
        return vehicles.availableAt(campId, startAt).stream()
                .filter(spec -> spec.id().value().equals(vehicleId))
                .findFirst()
                .orElseThrow(() -> new ConflictException("라우트의 차량을 찾을 수 없습니다",
                        Map.of("routeId", routeId.toString())));
    }
}
