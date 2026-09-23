package com.dawnline.dispatch.application;

import com.dawnline.common.error.ConflictException;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.dispatch.application.port.in.ReplanRouteUseCase;
import com.dawnline.dispatch.application.port.out.DispatchEvents;
import com.dawnline.dispatch.application.port.out.PlannedRouteRepository;
import com.dawnline.dispatch.application.port.out.RouteMutations;
import com.dawnline.dispatch.application.port.out.RouteMutations.PositionedStop;
import com.dawnline.dispatch.application.port.out.RouteMutations.RouteHeader;
import com.dawnline.dispatch.application.port.out.RouteMutations.SettledStop;
import com.dawnline.dispatch.application.port.out.RoutePlanRepository;
import com.dawnline.dispatch.application.port.out.RouteSnapshot;
import com.dawnline.dispatch.application.port.out.RuleCatalog;
import com.dawnline.dispatch.application.port.out.VehicleCatalog;
import com.dawnline.dispatch.domain.RoutePlan;
import com.dawnline.dispatch.domain.optimizer.CampDepot;
import com.dawnline.dispatch.domain.optimizer.CostModel;
import com.dawnline.dispatch.domain.optimizer.DistanceProvider;
import com.dawnline.dispatch.domain.optimizer.Explanation;
import com.dawnline.dispatch.domain.optimizer.Feasibility;
import com.dawnline.dispatch.domain.optimizer.OrderId;
import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import com.dawnline.dispatch.domain.optimizer.RelocateSearch;
import com.dawnline.dispatch.domain.optimizer.RouteAccumulator;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code delivery.at-risk} → 부분 재계획 (DESIGN.md §6.8, ADR-048).
 *
 * <h2>입력은 자기 DB 다</h2>
 * 남은 stop 은 {@code route_stops.status} 가, 편차는 {@code route_stops.actual_at} 이 말한다.
 * 페이로드의 {@code deviationSeconds} 는 <strong>대조값</strong>이고, 갈리면 센다 —
 * 버리지도 않고 입력으로 쓰지도 않는다(ADR-048 결정 1·2). 페이로드에서 편차를 읽으면
 * 「진실 하나」가 <em>소속은 dispatch · 시각은 tracking</em> 으로 갈린다.
 *
 * <h2>시계가 둘이다</h2>
 * <strong>평가</strong>는 {@code 계획 시작 + 편차} 로 하고 <strong>저장</strong>은 계획 시계
 * 그대로 한다. 편차를 저장까지 반영하면 {@code actual_at − planned_arrival} 에서 빼는 쪽이 방금
 * 밀려 <em>다음 편차의 기준선이 사라진다</em> — 두 번째 at-risk 에서 이 서비스는 자기 편차를
 * 0 으로 본다. 반대편에서 말하면 {@code planned_arrival} 은 계획이고 ETA 는 tracking 의 것이다.
 *
 * <h2>쿨다운이 가장 먼저다</h2>
 * 아무것도 읽기 전에 {@link RouteMutations#tryStartReplan} 을 부른다. 멱등 소비자는 이 자리를
 * 대신하지 못한다 — 두 at-risk 는 {@code eventId} 가 달라 둘 다 처음 보는 이벤트다
 * ([ADR-046] 결정 3). 「쿨다운은 이미 있으니 됐다」가 이 자리의 함정이다.
 *
 * <h2>실패하지 않는다 — 갈린다</h2>
 * 후보가 없든 이득이 없든 {@link Outcome} 을 돌려주고 소비는 성공한다. DLQ 로 보내면 고칠 수
 * 없는 것이 재시도되고 사람이 열어도 할 일이 없다(ADR-048 결정 5). 예외는 <em>정말로 처리하지
 * 못한 것</em>에만 남겨 둔다 — 계획이 없다, 차량이 없다, 받는 쪽이 하드 룰을 어겼다.
 */
public class ReplanRouteService implements ReplanRouteUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReplanRouteService.class);

    private final RouteMutations routes;
    private final RoutePlanRepository plans;
    private final PlannedRouteRepository explanations;
    private final VehicleCatalog vehicles;
    private final RuleCatalog rules;
    private final DispatchEvents events;
    private final DistanceProvider distance;
    private final DispatchMetrics metrics;
    private final Clock clock;
    private final Duration cooldown;
    private final Duration deviationTolerance;
    private final CostModel cost = new CostModel();
    private final RelocateSearch search;

    /**
     * @param routes             라우트 조작
     * @param plans              계획 저장소 (캠프 좌표가 여기 있다)
     * @param explanations       설명 저장소 (§6.3)
     * @param vehicles           차량 카탈로그
     * @param rules              룰 카탈로그
     * @param events             발행
     * @param distance           거리 제공자
     * @param metrics            §9.1 메트릭
     * @param clock              주입된 시계 (불변규칙 12)
     * @param cooldown           라우트당 쿨다운 (§6.8 5단계)
     * @param deviationTolerance 두 편차가 이만큼까지는 갈려도 세지 않는다
     */
    public ReplanRouteService(RouteMutations routes, RoutePlanRepository plans,
            PlannedRouteRepository explanations, VehicleCatalog vehicles, RuleCatalog rules,
            DispatchEvents events, DistanceProvider distance, DispatchMetrics metrics, Clock clock,
            Duration cooldown, Duration deviationTolerance) {

        this.routes = Objects.requireNonNull(routes, "routes");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.explanations = Objects.requireNonNull(explanations, "explanations");
        this.vehicles = Objects.requireNonNull(vehicles, "vehicles");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.events = Objects.requireNonNull(events, "events");
        this.distance = Objects.requireNonNull(distance, "distance");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
        this.deviationTolerance = Objects.requireNonNull(deviationTolerance, "deviationTolerance");
        this.search = new RelocateSearch(distance, cost);
    }

    @Override
    @Transactional
    public Outcome replan(ReplanCommand command) {
        Objects.requireNonNull(command, "command");

        if (!routes.tryStartReplan(command.routeId(), clock.instant(), cooldown)) {
            log.debug("쿨다운 안이라 재계획하지 않는다. routeId={}", command.routeId());
            return Outcome.COOLDOWN;
        }

        RouteHeader header = routes.findHeader(command.routeId())
                .orElseThrow(() -> NotFoundException.of("Route", command.routeId().toString()));
        RoutePlan plan = plans.findById(header.planId())
                .orElseThrow(() -> NotFoundException.of("RoutePlan", header.planId().toString()));
        if (!plan.campId().equals(command.campId())) {
            // 조용히 넘기지 않는다. 자기 DB 를 쓰되, 갈렸다는 사실은 남긴다 — 페이로드의 캠프는
            // ops 를 위한 스냅샷이고(불변규칙 4), 그것이 우리 계획과 다르면 둘 중 하나가 틀렸다.
            log.warn("at-risk 의 캠프가 계획과 다르다. routeId={}, 페이로드={}, 계획={}",
                    command.routeId(), command.campId(), plan.campId());
        }

        Instant startAt = plan.startedAt().orElseThrow(() -> new ConflictException(
                "시작 시각이 없는 계획의 라우트는 다시 풀 수 없습니다",
                Map.of("planId", plan.id().toString())));
        CampDepot depot = new CampDepot(plan.campId(), plan.depot().orElseThrow(
                () -> new ConflictException("캠프 좌표가 없는 계획은 다시 풀 수 없습니다",
                        Map.of("planId", plan.id().toString()))));

        SettledStop anchor = routes.lastSettledStop(command.routeId()).orElse(null);
        if (anchor == null) {
            // 편차를 «모른다». 0 으로 두고 돌리면 출발 지연 라우트가 「이득 없음」으로 조용히
            // 닫힌다 — 모름은 0 이 아니다(ADR-048 결정 1, 기각 (8)). 첫 ARRIVED 가 stop 하나를
            // 닿게 하고 tracking 의 쿨다운이 다시 발화하므로 구멍은 stop 하나 뒤에 닫힌다.
            log.debug("닿은 stop 이 없어 편차를 모른다. routeId={}", command.routeId());
            return Outcome.NO_ANCHOR;
        }
        compareDeviation(command, anchor.deviation());

        Map<UUID, VehicleSpec> fleet = fleetOf(plan.campId(), startAt);
        RuleSet ruleSet = rules.forCamp(plan.campId());
        RelocateSearch.RouteInput source = inputOf(header, fleet, depot, startAt,
                anchor.deviation());
        if (source.stops().size() == source.frozen()) {
            log.debug("남은 stop 이 없다. routeId={}", command.routeId());
            return Outcome.NO_CANDIDATE;
        }

        List<RelocateSearch.RouteInput> candidates = candidatesOf(header, fleet, depot, startAt);
        if (candidates.isEmpty()) {
            log.debug("받을 라우트가 없다. routeId={}, planId={}", command.routeId(), plan.id());
            return Outcome.NO_CANDIDATE;
        }

        RelocateSearch.Outcome found = search.search(ruleSet, source, candidates);
        if (!found.moved()) {
            log.debug("옮겨도 총비용이 줄지 않는다. routeId={}", command.routeId());
            return Outcome.NO_GAIN;
        }

        apply(plan, found, fleet, depot, startAt, ruleSet);
        log.info("부분 재계획을 반영했다. routeId={}, 이동 {}건, 절감 {}원, 편차 {}초",
                command.routeId(), found.moves().size(), found.gainKrw(),
                anchor.deviation().toSeconds());
        return Outcome.APPLIED;
    }

    /**
     * 두 편차를 견준다 — <strong>버리지도 않고 입력으로 쓰지도 않는다</strong>.
     *
     * <p>갈린다는 것은 tracking 과 dispatch 가 같은 라우트를 다르게 보고 있다는 뜻이고, 그
     * 사실이 먼저 필요하다(ADR-048 결정 2).
     */
    private void compareDeviation(ReplanCommand command, Duration mine) {
        Duration gap = mine.minus(command.deviation()).abs();
        if (gap.compareTo(deviationTolerance) > 0) {
            log.info("편차가 갈렸다. routeId={}, tracking={}초, dispatch={}초",
                    command.routeId(), command.deviationSeconds(), mine.toSeconds());
            metrics.atRiskDeviationMismatch();
        }
    }

    /**
     * 라우트 하나를 탐색 입력으로 — <strong>평가 시계는 밀려 있다</strong>.
     *
     * <p>얼어 있는 앞자락은 「기사가 가장 멀리 닿은 stop 의 {@code seq} 이하」다. 목록의 자리가
     * 아니라 저장된 순번으로 세는 이유: 취소된 stop 이 빠져 있어 둘이 다르다.
     */
    private RelocateSearch.RouteInput inputOf(RouteHeader header, Map<UUID, VehicleSpec> fleet,
            CampDepot depot, Instant startAt, Duration deviation) {

        List<PositionedStop> positioned = routes.loadPositionedStops(header.routeId());
        int settledSeq = routes.lastSettledStop(header.routeId()).map(SettledStop::seq).orElse(0);
        int frozen = (int) positioned.stream().filter(stop -> stop.seq() <= settledSeq).count();
        VehicleSpec vehicle = fleet.get(header.vehicleId());
        if (vehicle == null) {
            throw new ConflictException("라우트의 차량을 찾을 수 없습니다",
                    Map.of("routeId", header.routeId().toString()));
        }
        return new RelocateSearch.RouteInput(header.routeId(), vehicle, depot,
                positioned.stream().map(PositionedStop::stop).toList(), frozen,
                startAt.plus(deviation));
    }

    /**
     * 같은 계획의 다른 라우트들 (§6.8 2단계).
     *
     * <p>「진행 중」과 「미출발」을 상태 칼럼으로 가르지 않는다 — 닿은 stop 이 있으면 떠난
     * 것이고, 그 사실이 {@code route_stops.actual_at} 에 있다. 각 후보는 <strong>자기 편차</strong>
     * 로 평가한다: 위험한 라우트의 편차를 남의 시계에 밀어 넣으면 멀쩡한 라우트가 늦어 보인다.
     *
     * <p>차량을 찾을 수 없는 라우트는 <strong>후보에서 뺀다</strong>. 원 라우트였다면 예외지만
     * (거기는 반드시 풀어야 한다) 후보는 하나 줄어들 뿐이다.
     */
    private List<RelocateSearch.RouteInput> candidatesOf(RouteHeader source,
            Map<UUID, VehicleSpec> fleet, CampDepot depot, Instant startAt) {

        List<RelocateSearch.RouteInput> candidates = new ArrayList<>();
        for (RouteHeader header : routes.routesOfPlan(source.planId())) {
            if (header.routeId().equals(source.routeId()) || !fleet.containsKey(header.vehicleId())) {
                continue;
            }
            Duration deviation = routes.lastSettledStop(header.routeId())
                    .map(SettledStop::deviation).orElse(Duration.ZERO);
            candidates.add(inputOf(header, fleet, depot, startAt, deviation));
        }
        return candidates;
    }

    /**
     * 옮기고, 두 라우트를 다시 쓰고, 둘 다 개정으로 발행한다 (ADR-048 결정 4 (c)).
     *
     * <p>저장은 <strong>계획 시계</strong>로 한다 — 평가에 쓴 밀린 시계가 아니다.
     */
    private void apply(RoutePlan plan, RelocateSearch.Outcome found, Map<UUID, VehicleSpec> fleet,
            CampDepot depot, Instant startAt, RuleSet ruleSet) {

        Map<UUID, UUID> vehicleOf = new LinkedHashMap<>();
        List<Explanation> reasons = new ArrayList<>();
        for (RelocateSearch.Move move : found.moves()) {
            for (OrderId orderId : move.orderIds()) {
                UUID stopId = routes.findStopOf(move.fromRouteId(), orderId.value())
                        .orElseThrow(() -> NotFoundException.of("RouteStop",
                                orderId.value().toString()));
                routes.moveOrder(stopId, orderId.value(), move.toRouteId());
            }
            UUID vehicleId = vehicleOf.computeIfAbsent(move.toRouteId(),
                    routeId -> routes.findHeader(routeId).orElseThrow().vehicleId());
            move.orderIds().forEach(orderId -> reasons.add(Explanation.relocated(orderId,
                    fleet.get(vehicleId).id(), move.fromRouteId(), move.toRouteId(),
                    move.gainKrw())));
        }

        found.sequences().forEach((routeId, stops) ->
                republish(plan, routeId, stops, fleet, depot, startAt, ruleSet));
        // 설명은 마지막이다 — 라우트가 실제로 옮겨진 뒤에야 「어디서 어디로」가 참이 된다.
        explanations.saveExplanations(plan.id(), reasons, Map.of());
    }

    /** 순서를 다시 쓰고 개정 번호를 올려 발행한다. */
    private void republish(RoutePlan plan, UUID routeId, List<Stop> stops,
            Map<UUID, VehicleSpec> fleet, CampDepot depot, Instant startAt, RuleSet ruleSet) {

        RouteHeader header = routes.findHeader(routeId)
                .orElseThrow(() -> NotFoundException.of("Route", routeId.toString()));
        if (stops.isEmpty()) {
            // 마지막 주문이 떠났다. route.assigned 의 stops 는 최소 1개라 발행하지 않는다 —
            // 빈 라우트를 개정으로 내면 받는 쪽이 「계획이 사라졌다」를 stop 0 으로 읽는다.
            routes.clear(routeId);
            routes.bumpRevision(routeId);
            return;
        }
        PlannedRoute rebuilt = rebuild(routeId, stops, fleet.get(header.vehicleId()), depot,
                startAt, ruleSet);
        routes.rewrite(routeId, rebuilt);
        int revision = routes.bumpRevision(routeId);
        RouteSnapshot snapshot = routes.snapshot(routeId)
                .orElseThrow(() -> NotFoundException.of("Route", routeId.toString()));
        // 계획 결과가 아니라 저장된 라우트에서 만든다 — 취소된 stop 이 PlannedRoute 에 없다
        // (ADR-026 결정 4). 재계획한 라우트에도 취소된 지점이 남아 있을 수 있다.
        events.routeRevised(plan, snapshot, revision);
    }

    /**
     * 그 순서로 라우트를 다시 만든다. 하드 룰을 어기면 트랜잭션이 통째로 되돌아간다.
     *
     * <p>받는 쪽에서는 탐색이 이미 통과를 확인했고, 여기서 다시 보는 이유는 <strong>시계가
     * 다르기 때문</strong>이다 — 평가는 밀린 시계, 저장은 계획 시계다. 여기서 걸리면 그것은
     * 재시도로 달라지지 않는 결함이고, 그래서 {@link Outcome} 이 아니라 예외다.
     */
    private PlannedRoute rebuild(UUID routeId, List<Stop> stops, VehicleSpec vehicle,
            CampDepot depot, Instant startAt, RuleSet ruleSet) {

        if (vehicle == null) {
            throw new ConflictException("라우트의 차량을 찾을 수 없습니다",
                    Map.of("routeId", routeId.toString()));
        }
        RouteAccumulator route = new RouteAccumulator(ruleSet, vehicle, depot, distance, startAt);
        for (Stop stop : stops) {
            Feasibility feasibility = route.check(stop);
            if (!feasibility.feasible()) {
                throw new ConflictException("재계획이 하드 룰을 어깁니다: " + feasibility.reason(),
                        Map.of("routeId", routeId.toString(), "rule", feasibility.ruleName()));
            }
            route.append(stop);
        }
        return route.toRoute(cost);
    }

    private Map<UUID, VehicleSpec> fleetOf(UUID campId, Instant startAt) {
        Map<UUID, VehicleSpec> fleet = new LinkedHashMap<>();
        vehicles.availableAt(campId, startAt)
                .forEach(vehicle -> fleet.put(vehicle.id().value(), vehicle));
        return fleet;
    }
}
