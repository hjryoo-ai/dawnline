package com.dawnline.dispatch.application;

import com.dawnline.common.error.ConflictException;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.dispatch.application.port.in.ReassignStopUseCase;
import com.dawnline.dispatch.application.port.out.DispatchEvents;
import com.dawnline.dispatch.application.port.out.RouteMutations;
import com.dawnline.dispatch.application.port.out.RoutePlanRepository;
import com.dawnline.dispatch.application.port.out.RuleCatalog;
import com.dawnline.dispatch.application.port.out.VehicleCatalog;
import com.dawnline.dispatch.domain.RoutePlan;
import com.dawnline.dispatch.domain.optimizer.CampDepot;
import com.dawnline.dispatch.domain.optimizer.CostModel;
import com.dawnline.dispatch.domain.optimizer.DistanceProvider;
import com.dawnline.dispatch.domain.optimizer.Feasibility;
import com.dawnline.dispatch.domain.optimizer.RouteAccumulator;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자가 stop 을 다른 라우트로 옮긴다 (DESIGN.md §5.3).
 *
 * <h2>옮긴 뒤 하드 룰을 다시 돌린다</h2>
 * 사람이 하는 조작이라도 <strong>용량·근무창·냉장을 어긴 라우트는 만들어질 수 없다</strong>.
 * 검증 없이 옮기면 그 라우트는 계획이 아니라 목록이 되고, {@code PlanValidator} 가 발행 직전에
 * 막으려던 것이 운영자 API 로 뒷문을 얻는다. 어기면 409 이고 트랜잭션이 통째로 되돌아간다.
 *
 * <h2>다시 풀지 않는다</h2>
 * §6.8 의 부분 재계획은 <em>알고리즘이</em> 다시 푸는 것이고 이것은 사람이 하나를 옮기는 것이다.
 * 남은 순서는 그대로 두고 시간만 재전파한다 — ADR-026 이 취소에 대해 정한 것과 같은 원칙이다.
 */
public class ReassignStopService implements ReassignStopUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReassignStopService.class);

    private final RouteMutations routes;
    private final RoutePlanRepository plans;
    private final VehicleCatalog vehicles;
    private final RuleCatalog rules;
    private final DispatchEvents events;
    private final DistanceProvider distance;
    private final CostModel cost = new CostModel();

    /**
     * @param routes   라우트 조작
     * @param plans    계획 저장소 (캠프 좌표가 여기 있다)
     * @param vehicles 차량 카탈로그
     * @param rules    룰 카탈로그
     * @param events   발행
     * @param distance 거리 제공자
     */
    public ReassignStopService(RouteMutations routes, RoutePlanRepository plans,
            VehicleCatalog vehicles, RuleCatalog rules, DispatchEvents events,
            DistanceProvider distance) {

        this.routes = Objects.requireNonNull(routes, "routes");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.vehicles = Objects.requireNonNull(vehicles, "vehicles");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.events = Objects.requireNonNull(events, "events");
        this.distance = Objects.requireNonNull(distance, "distance");
    }

    @Override
    @Transactional
    public Result reassign(UUID routeId, UUID orderId, UUID targetRouteId) {
        if (routeId.equals(targetRouteId)) {
            throw new ConflictException("같은 라우트로는 옮길 수 없습니다",
                    Map.of("routeId", routeId.toString()));
        }
        RouteMutations.RouteHeader from = header(routeId);
        RouteMutations.RouteHeader to = header(targetRouteId);
        if (!from.planId().equals(to.planId())) {
            // 다른 계획의 라우트로 옮기면 두 계획의 배정 수와 비용이 서로 어긋난다.
            throw new ConflictException("다른 계획의 라우트로는 옮길 수 없습니다",
                    Map.of("from", from.planId().toString(), "to", to.planId().toString()));
        }
        UUID stopId = routes.findStopOf(routeId, orderId)
                .orElseThrow(() -> NotFoundException.of("RouteStop", orderId.toString()));

        RoutePlan plan = plans.findById(from.planId()).orElseThrow(
                () -> NotFoundException.of("RoutePlan", from.planId().toString()));
        Instant startAt = plan.startedAt().orElseThrow(() -> new ConflictException(
                "시작 시각이 없는 계획의 라우트는 옮길 수 없습니다",
                Map.of("planId", plan.id().toString())));
        CampDepot depot = new CampDepot(plan.campId(), plan.depot().orElseThrow(
                () -> new ConflictException("캠프 좌표가 없는 계획은 다시 쓸 수 없습니다",
                        Map.of("planId", plan.id().toString()))));

        routes.moveOrder(stopId, orderId, targetRouteId);

        List<Stop> fromStops = routes.loadStops(routeId);
        List<Stop> toStops = routes.loadStops(targetRouteId);
        RuleSet ruleSet = rules.forCamp(plan.campId());
        Map<UUID, VehicleSpec> fleet = fleetOf(plan.campId(), startAt);

        // 두 라우트 모두 다시 검사한다 — 떠난 쪽은 규칙을 어길 수 없지만, 시간이 당겨져
        // 지각 판정이 바뀔 수 있고 그 사실이 발행에 실려야 한다.
        var fromRoute = rebuild(routeId, fromStops, fleet.get(from.vehicleId()), depot, startAt,
                ruleSet);
        var toRoute = rebuild(targetRouteId, toStops, fleet.get(to.vehicleId()), depot, startAt,
                ruleSet);

        int fromRevision = routes.bumpRevision(routeId);
        int toRevision = routes.bumpRevision(targetRouteId);
        if (fromRoute == null) {
            routes.clear(routeId);
        } else {
            routes.rewrite(routeId, fromRoute);
            events.routeAssigned(plan, routeId, fromRoute, fromRevision);
        }
        routes.rewrite(targetRouteId, toRoute);
        events.routeAssigned(plan, targetRouteId, toRoute, toRevision);

        log.info("stop 재배정: orderId={} {}(rev {}) → {}(rev {})", orderId, routeId, fromRevision,
                targetRouteId, toRevision);
        return new Result(orderId, routeId, fromRevision, targetRouteId, toRevision);
    }

    private RouteMutations.RouteHeader header(UUID routeId) {
        return routes.findHeader(routeId)
                .orElseThrow(() -> NotFoundException.of("Route", routeId.toString()));
    }

    private Map<UUID, VehicleSpec> fleetOf(UUID campId, Instant startAt) {
        Map<UUID, VehicleSpec> fleet = new java.util.LinkedHashMap<>();
        vehicles.availableAt(campId, startAt)
                .forEach(vehicle -> fleet.put(vehicle.id().value(), vehicle));
        return fleet;
    }

    /**
     * 순서를 그대로 두고 하드 룰을 다시 돌리며 시간을 재전파한다. 어기면 트랜잭션이 되돌아간다.
     *
     * @return 다시 계산된 라우트. stop 이 하나도 없으면 {@code null}
     */
    private com.dawnline.dispatch.domain.optimizer.PlannedRoute rebuild(UUID routeId,
            List<Stop> stops, VehicleSpec vehicle, CampDepot depot, Instant startAt,
            RuleSet ruleSet) {

        if (vehicle == null) {
            throw new ConflictException("라우트의 차량을 찾을 수 없습니다",
                    Map.of("routeId", routeId.toString()));
        }
        if (stops.isEmpty()) {
            // 마지막 주문이 떠났다. route.assigned 의 stops 는 최소 1개라 발행하지 않는다.
            return null;
        }
        RouteAccumulator route = new RouteAccumulator(ruleSet, vehicle, depot, distance, startAt);
        for (Stop stop : stops) {
            Feasibility feasibility = route.check(stop);
            if (!feasibility.feasible()) {
                throw new ConflictException("옮기면 하드 룰을 어깁니다: " + feasibility.reason(),
                        Map.of("routeId", routeId.toString(), "rule", feasibility.ruleName()));
            }
            route.append(stop);
        }
        return route.toRoute(cost);
    }
}
