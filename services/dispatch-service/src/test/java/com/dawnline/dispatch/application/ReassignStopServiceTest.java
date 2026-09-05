package com.dawnline.dispatch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.common.error.ConflictException;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.dispatch.application.port.out.RouteMutations;
import com.dawnline.dispatch.domain.PlanMode;
import com.dawnline.dispatch.domain.RoutePlan;
import com.dawnline.dispatch.domain.optimizer.HaversineDistance;
import com.dawnline.dispatch.domain.optimizer.OrderId;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.rule.DispatchRules;
import com.dawnline.dispatch.domain.optimizer.rule.RuleDefinition;
import com.dawnline.dispatch.domain.optimizer.rule.RuleSeverity;
import com.dawnline.dispatch.domain.optimizer.rule.RuleType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 운영자 재배정 (§5.3). 검증 없이 옮기면 용량을 어긴 라우트가 조용히 생긴다. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ReassignStopServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-06T01:00:00Z");
    private static final UUID CAMP_ID = Ids.newId();
    private static final GeoPoint NEAR = GeoPoint.of(37.5700, 126.9779);
    private static final GeoPoint FAR = GeoPoint.of(37.5900, 126.9779);
    private static final TimeWindow WINDOW =
            new TimeWindow(NOW, NOW.plus(Duration.ofHours(8)));

    private final InMemoryDispatchPorts.Plans plans = new InMemoryDispatchPorts.Plans();
    private final InMemoryDispatchPorts.Events events = new InMemoryDispatchPorts.Events();
    private final FakeRoutes routes = new FakeRoutes();

    /** 라우트 조작의 메모리 구현 — 옮기기와 다시 쓰기가 실제로 무엇을 바꾸는지 본다. */
    private final class FakeRoutes implements RouteMutations {

        private final Map<UUID, RouteHeader> headers = new LinkedHashMap<>();
        private final Map<UUID, List<Stop>> stops = new LinkedHashMap<>();
        private final Map<UUID, Integer> revisions = new LinkedHashMap<>();
        private final Map<UUID, PlannedRoute> written = new LinkedHashMap<>();
        private final List<UUID> cleared = new ArrayList<>();

        @Override
        public Optional<RouteHeader> findHeader(UUID routeId) {
            return Optional.ofNullable(headers.get(routeId));
        }

        @Override
        public List<Stop> loadStops(UUID routeId) {
            return List.copyOf(stops.getOrDefault(routeId, List.of()));
        }

        @Override
        public Optional<UUID> findStopOf(UUID routeId, UUID orderId) {
            return stops.getOrDefault(routeId, List.of()).stream()
                    .filter(stop -> stop.orderIds().stream()
                            .anyMatch(id -> id.value().equals(orderId)))
                    .map(stop -> UUID.nameUUIDFromBytes(stop.point().toString().getBytes()))
                    .findFirst();
        }

        @Override
        public void moveOrder(UUID fromStopId, UUID orderId, UUID targetRouteId) {
            UUID from = headers.keySet().stream()
                    .filter(routeId -> findStopOf(routeId, orderId).isPresent())
                    .findFirst().orElseThrow();
            List<Stop> source = new ArrayList<>(stops.get(from));
            Stop moved = source.stream().filter(stop -> stop.orderIds().stream()
                    .anyMatch(id -> id.value().equals(orderId))).findFirst().orElseThrow();
            source.remove(moved);
            stops.put(from, source);

            List<Stop> target = new ArrayList<>(stops.getOrDefault(targetRouteId, List.of()));
            target.add(moved);
            stops.put(targetRouteId, target);
        }

        @Override
        public void rewrite(UUID routeId, PlannedRoute route) {
            written.put(routeId, route);
        }

        @Override
        public void clear(UUID routeId) {
            cleared.add(routeId);
        }

        @Override
        public int bumpRevision(UUID routeId) {
            return revisions.merge(routeId, 1, Integer::sum);
        }

        private UUID route(UUID planId, UUID vehicleId, List<Stop> initial) {
            UUID routeId = Ids.newId();
            headers.put(routeId, new RouteHeader(routeId, planId, vehicleId));
            stops.put(routeId, new ArrayList<>(initial));
            revisions.put(routeId, 1);
            return routeId;
        }
    }

    private static Stop stop(GeoPoint point, int weightG) {
        return new Stop(point, List.of(OrderId.of(Ids.newId())),
                new Parcel(weightG, 1, false, false), WINDOW, 60, 0);
    }

    private RoutePlan plan() {
        RoutePlan plan = RoutePlan.request(Ids.newId(), Ids.newId(), CAMP_ID,
                InMemoryDispatchPorts.CAMP);
        plans.insertIfAbsent(plan);
        plan.begin("baseline-nn", PlanMode.FULL, 1L, 1, NOW);
        plans.update(plan);
        return plan;
    }

    private ReassignStopService service(RuleSet rules, int maxWeightG) {
        return new ReassignStopService(routes, plans,
                (campId, at) -> capped(maxWeightG),
                campId -> rules, events, new HaversineDistance(1.3d, 25.0d));
    }

    private List<com.dawnline.dispatch.domain.optimizer.VehicleSpec> capped(int maxWeightG) {
        return routes.headers.values().stream()
                .map(header -> new com.dawnline.dispatch.domain.optimizer.VehicleSpec(
                        com.dawnline.dispatch.domain.optimizer.VehicleId.of(header.vehicleId()),
                        new com.dawnline.dispatch.domain.optimizer.Capacity(maxWeightG, 10_000_000),
                        new com.dawnline.dispatch.domain.optimizer.VehicleAttrs("VAN", false, false),
                        new TimeWindow(NOW, NOW.plus(Duration.ofHours(10))),
                        com.dawnline.dispatch.domain.optimizer.VehicleCost.krw(45_000, 600, 250)))
                .distinct().toList();
    }

    @Test
    void 옮기면_두_라우트_모두_개정된다() {
        RoutePlan plan = plan();
        Stop moving = stop(NEAR, 1_000);
        UUID from = routes.route(plan.id(), Ids.newId(), List.of(moving, stop(FAR, 1_000)));
        UUID to = routes.route(plan.id(), Ids.newId(), List.of(stop(FAR, 1_000)));

        var result = service(RuleSet.empty(), 1_000_000)
                .reassign(from, moving.orderIds().getFirst().value(), to);

        assertThat(result.fromRevision()).isEqualTo(2);
        assertThat(result.toRevision()).isEqualTo(2);
        // 소비자는 이미 본 revision 이하를 무시한다 — 둘 다 다시 나가야 한다 (§6.8 4단계).
        assertThat(events.routesAssigned).containsExactlyInAnyOrder(from, to);
    }

    @Test
    void 옮겨서_용량을_넘기면_409_다() {
        // 사람이 하는 조작이라도 용량을 어긴 라우트는 만들어질 수 없다.
        RoutePlan plan = plan();
        Stop moving = stop(NEAR, 600_000);
        UUID from = routes.route(plan.id(), Ids.newId(), List.of(moving));
        UUID to = routes.route(plan.id(), Ids.newId(), List.of(stop(FAR, 600_000)));
        RuleSet rules = DispatchRules.ruleSet(List.of(new RuleDefinition("capacity",
                RuleType.VEHICLE_CAPACITY, RuleSeverity.HARD, 15, Map.of())), 1);

        assertThatThrownBy(() -> service(rules, 1_000_000)
                .reassign(from, moving.orderIds().getFirst().value(), to))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("하드 룰");
    }

    @Test
    void 다른_계획의_라우트로는_옮길_수_없다() {
        // 두 계획의 배정 수와 비용이 서로 어긋난다.
        RoutePlan first = plan();
        RoutePlan second = plan();
        Stop moving = stop(NEAR, 1_000);
        UUID from = routes.route(first.id(), Ids.newId(), List.of(moving));
        UUID to = routes.route(second.id(), Ids.newId(), List.of(stop(FAR, 1_000)));

        assertThatThrownBy(() -> service(RuleSet.empty(), 1_000_000)
                .reassign(from, moving.orderIds().getFirst().value(), to))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("다른 계획");
    }

    @Test
    void 같은_라우트로는_옮길_수_없다() {
        RoutePlan plan = plan();
        Stop moving = stop(NEAR, 1_000);
        UUID from = routes.route(plan.id(), Ids.newId(), List.of(moving));

        assertThatThrownBy(() -> service(RuleSet.empty(), 1_000_000)
                .reassign(from, moving.orderIds().getFirst().value(), from))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 없는_라우트는_404_다() {
        RoutePlan plan = plan();
        UUID from = routes.route(plan.id(), Ids.newId(), List.of(stop(NEAR, 1_000)));

        assertThatThrownBy(() -> service(RuleSet.empty(), 1_000_000)
                .reassign(from, Ids.newId(), Ids.newId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 그_라우트에_없는_주문은_404_다() {
        RoutePlan plan = plan();
        UUID from = routes.route(plan.id(), Ids.newId(), List.of(stop(NEAR, 1_000)));
        UUID to = routes.route(plan.id(), Ids.newId(), List.of(stop(FAR, 1_000)));

        assertThatThrownBy(() -> service(RuleSet.empty(), 1_000_000)
                .reassign(from, Ids.newId(), to))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 마지막_주문이_떠나면_라우트를_비운다() {
        // route.assigned 의 stops 는 최소 1개다 — 빈 라우트는 발행하지 않는다.
        RoutePlan plan = plan();
        Stop only = stop(NEAR, 1_000);
        UUID from = routes.route(plan.id(), Ids.newId(), List.of(only));
        UUID to = routes.route(plan.id(), Ids.newId(), List.of(stop(FAR, 1_000)));

        service(RuleSet.empty(), 1_000_000).reassign(from, only.orderIds().getFirst().value(), to);

        assertThat(routes.cleared).containsExactly(from);
        assertThat(events.routesAssigned).containsExactly(to);
    }

    @Test
    void 시간이_재전파된다() {
        RoutePlan plan = plan();
        Stop moving = stop(NEAR, 1_000);
        UUID from = routes.route(plan.id(), Ids.newId(), List.of(moving, stop(FAR, 1_000)));
        UUID to = routes.route(plan.id(), Ids.newId(), List.of(stop(FAR, 1_000)));

        service(RuleSet.empty(), 1_000_000).reassign(from, moving.orderIds().getFirst().value(), to);

        PlannedRoute rewritten = routes.written.get(to);
        assertThat(rewritten.stops()).hasSize(2);
        assertThat(rewritten.stops().getFirst().departure())
                .isBeforeOrEqualTo(rewritten.stops().get(1).arrival());
        assertThat(rewritten.distanceM()).isPositive();
    }
}
