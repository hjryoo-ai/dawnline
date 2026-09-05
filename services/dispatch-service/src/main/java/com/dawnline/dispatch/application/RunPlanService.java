package com.dawnline.dispatch.application;

import com.dawnline.common.Ids;
import com.dawnline.dispatch.application.port.in.RunPlanCommand;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.application.port.out.DispatchEvents;
import com.dawnline.dispatch.application.port.out.PlannedRouteRepository;
import com.dawnline.dispatch.application.port.out.RoutePlanRepository;
import com.dawnline.dispatch.application.port.out.RuleCatalog;
import com.dawnline.dispatch.application.port.out.VehicleCatalog;
import com.dawnline.dispatch.domain.CandidateStatus;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.dispatch.domain.PlanStatus;
import com.dawnline.dispatch.domain.RoutePlan;
import com.dawnline.dispatch.domain.optimizer.Candidate;
import com.dawnline.dispatch.domain.optimizer.CampDepot;
import com.dawnline.dispatch.domain.optimizer.CostModel;
import com.dawnline.dispatch.domain.optimizer.DispatchStrategies;
import com.dawnline.dispatch.domain.optimizer.DistanceProvider;
import com.dawnline.dispatch.domain.optimizer.OrderId;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.PlanPruner;
import com.dawnline.dispatch.domain.optimizer.PlanResult;
import com.dawnline.dispatch.domain.optimizer.PlanValidator;
import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import com.dawnline.dispatch.domain.optimizer.PlannedStop;
import com.dawnline.dispatch.domain.optimizer.PlanningBudget;
import com.dawnline.dispatch.domain.optimizer.PlanningProblem;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import com.dawnline.dispatch.domain.optimizer.VehicleId;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import com.dawnline.dispatch.domain.optimizer.WaveRef;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * 웨이브 하나를 계획하고 발행한다 (DESIGN.md §5.3, §6.5).
 *
 * <h2>한 트랜잭션이다</h2>
 * 계획 저장·후보 상태 전이·세 이벤트의 outbox 적재가 <strong>모두 같은 트랜잭션</strong>이다.
 * 나눠 넣으면 "완료라는데 라우트가 없다" 가 생긴다(ADR-024). 계획 <em>계산</em>은 순수 함수라
 * 트랜잭션 안에서 도는 것이 부담이지만, {@code large} 실측이 674 ms 라 지금 규모에서는
 * 나누는 복잡도가 더 비싸다 — 재검토 지점은 §6.7 의 예산(30초)에 가까워질 때다.
 *
 * <h2>발행 직전 재검증</h2>
 * 계획은 시작 시점 스냅샷으로 돈다. 그 사이 도착한 취소는 반영되지 않았으므로, 발행 직전에
 * 후보 상태를 <strong>다시 읽어</strong> 취소된 것을 뺀다(§6.5 6단계, ADR-026 분기 2).
 * 이 창을 revision 없이 닫는 유일한 자리다.
 */
public class RunPlanService implements RunPlanUseCase {

    private static final Logger log = LoggerFactory.getLogger(RunPlanService.class);

    /**
     * 계획할 후보가 하나도 없을 때의 {@code plan.failed.reason}.
     *
     * <p>계약의 enum 을 2026-09-05 에 넓혔다 — 스키마가 "사유가 늘면 같은 major 안에서 enum 을
     * 넓힌다" 고 적어 둔 그대로다(§4.7). 소비자는 이 값을 문자열로 받아 기록만 하므로 넓혀도
     * 깨지지 않는다.
     */
    static final String NO_CANDIDATES = "NO_CANDIDATES";

    /**
     * 하드 룰을 어긴 결과가 나왔을 때의 사유. <strong>데이터가 아니라 코드 문제다</strong> —
     * 배치할 때 검사한 것과 최종 산출물이 달라졌다는 뜻이다(§6.5 6단계).
     */
    static final String RULE_VIOLATION = "RULE_VIOLATION";

    private final RoutePlanRepository plans;
    private final DispatchCandidateRepository candidates;
    private final PlannedRouteRepository routes;
    private final DispatchEvents events;
    private final VehicleCatalog vehicles;
    private final RuleCatalog rules;
    private final CampLocator camps;
    private final DistanceProvider distance;
    private final PlanValidator validator = new PlanValidator();
    private final CostModel cost = new CostModel();
    private final Clock clock;
    private final String defaultStrategy;
    private final PlanningBudget budget;

    /**
     * @param plans           계획 저장소
     * @param candidates      후보 저장소
     * @param routes          라우트·설명 저장소
     * @param events          발행 (Outbox)
     * @param vehicles        차량 카탈로그
     * @param rules           룰 카탈로그
     * @param camps           캠프 좌표
     * @param distance        거리 제공자
     * @param clock           시각 출처 (불변규칙 12)
     * @param defaultStrategy 기본 전략 (§6.6)
     * @param budget          시간 예산 (§6.7)
     */
    public RunPlanService(RoutePlanRepository plans, DispatchCandidateRepository candidates,
            PlannedRouteRepository routes, DispatchEvents events, VehicleCatalog vehicles,
            RuleCatalog rules, CampLocator camps, DistanceProvider distance, Clock clock,
            String defaultStrategy, PlanningBudget budget) {

        this.plans = Objects.requireNonNull(plans, "plans");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.events = Objects.requireNonNull(events, "events");
        this.vehicles = Objects.requireNonNull(vehicles, "vehicles");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.camps = Objects.requireNonNull(camps, "camps");
        this.distance = Objects.requireNonNull(distance, "distance");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.defaultStrategy = Objects.requireNonNull(defaultStrategy, "defaultStrategy");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    @Override
    @Transactional
    public Outcome run(RunPlanCommand command) {
        Objects.requireNonNull(command, "command");
        Instant startedAt = clock.instant();

        RoutePlan plan = openPlan(command);
        if (plan.status().isTerminal()) {
            // wave.closed 중복 도착. 멱등 소비자와 wave_id UNIQUE 로 두 겹이다 (§5.3).
            log.debug("이미 발행된 웨이브입니다: waveId={}", command.waveId());
            return Outcome.ALREADY_PUBLISHED;
        }

        List<DispatchCandidate> plannable = candidates.findPlannableInWave(command.waveId());
        if (plannable.isEmpty()) {
            plan.begin(strategyOf(command), command.effectiveMode(), command.effectiveSeed(), 0,
                    startedAt);
            plan.fail(NO_CANDIDATES, clock.instant());
            plans.update(plan);
            events.planFailed(plan);
            return Outcome.NO_CANDIDATES;
        }

        RuleSet ruleSet = rules.forCamp(command.campId());
        plan.begin(strategyOf(command), command.effectiveMode(), command.effectiveSeed(),
                ruleSet.version(), startedAt);
        plans.update(plan);

        PlanningProblem problem = problemOf(command, plannable, ruleSet, startedAt);
        PlanResult result = DispatchStrategies.create(strategyOf(command)).plan(problem);

        List<PlanValidator.Violation> violations = validator.validate(problem, result);
        if (!violations.isEmpty()) {
            // 하드 룰을 어긴 계획은 데이터 문제가 아니라 코드 버그다 (§6.5 6단계).
            log.error("계획이 하드 룰을 어겼습니다: waveId={} 위반={}건 첫 위반={}",
                    command.waveId(), violations.size(), violations.getFirst().feasibility());
            plan.fail(RULE_VIOLATION, clock.instant());
            plans.update(plan);
            events.planFailed(plan);
            return Outcome.FAILED;
        }

        // 계획 중에 취소된 주문을 뺀다 (ADR-026 분기 2) — revision 없이 닫는 유일한 창이다.
        result = PlanPruner.prune(result, cancelledSince(command.waveId(), plannable));

        if (result.routes().isEmpty()) {
            plan.fail(NO_CANDIDATES, clock.instant());
            plans.update(plan);
            events.planFailed(plan);
            return Outcome.FAILED;
        }

        return publish(command, plan, result, startedAt);
    }

    private Outcome publish(RunPlanCommand command, RoutePlan plan, PlanResult result,
            Instant startedAt) {

        Instant finishedAt = clock.instant();
        int durationMs = (int) Duration.between(startedAt, finishedAt).toMillis();
        plan.complete(result.totalCost(), result.assignedOrderCount(), result.unassigned().size(),
                durationMs, finishedAt);

        List<UUID> routeIds = routes.saveRoutes(plan.id(), result.routes());
        Map<VehicleId, UUID> byVehicle = new LinkedHashMap<>();
        for (int i = 0; i < result.routes().size(); i++) {
            byVehicle.put(result.routes().get(i).vehicle(), routeIds.get(i));
        }
        routes.saveExplanations(plan.id(), result.explanations(), byVehicle);

        markCandidates(result, finishedAt);

        // 세 이벤트가 같은 트랜잭션이다 (ADR-024). 나눠 넣으면 "완료라는데 라우트가 없다".
        for (int i = 0; i < result.routes().size(); i++) {
            PlannedRoute route = result.routes().get(i);
            UUID routeId = routeIds.get(i);
            events.routeAssigned(plan, routeId, route, 1);
            events.ordersDispatched(routeId, orderIdsOf(route));
        }
        plan.publish(finishedAt);
        plans.update(plan);
        events.planCompleted(plan, result);
        return Outcome.PUBLISHED;
    }

    /** 계획 결과를 후보 상태에 반영한다. 늦게 온 취소는 축 규칙이 지켜 준다. */
    private void markCandidates(PlanResult result, Instant at) {
        Set<UUID> assigned = new LinkedHashSet<>();
        result.routes().forEach(route -> route.stops().forEach(stop ->
                stop.stop().orderIds().forEach(orderId -> assigned.add(orderId.value()))));
        applyStatus(assigned, CandidateStatus.PLANNED, at);

        Set<UUID> unassigned = new LinkedHashSet<>();
        result.unassigned().forEach(entry -> unassigned.add(entry.orderId().value()));
        applyStatus(unassigned, CandidateStatus.UNASSIGNED, at);
    }

    private void applyStatus(Set<UUID> orderIds, CandidateStatus status, Instant at) {
        for (UUID orderId : orderIds) {
            candidates.findById(orderId).ifPresent(candidate -> {
                if (candidate.recordPlanResult(status, at)) {
                    candidates.update(candidate);
                }
            });
        }
    }

    /** 계획을 시작한 뒤 취소된 주문들. 발행 직전 재검증이 쓰는 값이다. */
    private Set<OrderId> cancelledSince(UUID waveId, List<DispatchCandidate> planned) {
        Set<UUID> stillPlannable = new LinkedHashSet<>();
        candidates.findPlannableInWave(waveId)
                .forEach(candidate -> stillPlannable.add(candidate.orderId()));

        Set<OrderId> cancelled = new LinkedHashSet<>();
        for (DispatchCandidate candidate : planned) {
            if (!stillPlannable.contains(candidate.orderId())) {
                cancelled.add(OrderId.of(candidate.orderId()));
            }
        }
        if (!cancelled.isEmpty()) {
            log.info("계획 중 취소된 주문을 발행에서 뺍니다: waveId={} {}건", waveId, cancelled.size());
        }
        return cancelled;
    }

    /** 있으면 그것, 없으면 새로 만든다. {@code wave_id} UNIQUE 가 경합을 흡수한다. */
    private RoutePlan openPlan(RunPlanCommand command) {
        Optional<RoutePlan> existing = plans.findByWaveId(command.waveId());
        if (existing.isPresent()) {
            RoutePlan plan = existing.get();
            if (plan.status() == PlanStatus.FAILED) {
                // 운영자 재실행 (§5.3). 성공하면 plan.completed 가 다시 나가 웨이브를
                // PLAN_FAILED → PLANNED 로 되돌린다 (ADR-024 결정 3).
                plan.requeue(clock.instant());
            }
            return plan;
        }
        RoutePlan plan = RoutePlan.request(Ids.newId(), command.waveId(), command.campId());
        if (!plans.insertIfAbsent(plan)) {
            return plans.findByWaveId(command.waveId()).orElseThrow(() ->
                    new IllegalStateException("계획을 넣지도 찾지도 못했습니다: " + command.waveId()));
        }
        return plan;
    }

    private PlanningProblem problemOf(RunPlanCommand command, List<DispatchCandidate> plannable,
            RuleSet ruleSet, Instant startedAt) {

        List<VehicleSpec> fleet = vehicles.availableAt(command.campId(), startedAt);
        if (fleet.isEmpty()) {
            throw new IllegalStateException("캠프에 가용 차량이 없습니다: " + command.campId());
        }
        List<Candidate> optimizerCandidates = new ArrayList<>(plannable.size());
        for (DispatchCandidate candidate : plannable) {
            optimizerCandidates.add(new Candidate(OrderId.of(candidate.orderId()),
                    candidate.location(),
                    new Parcel(candidate.weightG(), candidate.volumeCm3(),
                            candidate.requiresCold(), candidate.hazmat()),
                    candidate.promised(), candidate.serviceSeconds(), candidate.priority()));
        }
        CampDepot depot = camps.locate(command.campId());
        return new PlanningProblem(
                new WaveRef(command.waveId(), command.campId(), "SAME_DAY", startedAt),
                depot, optimizerCandidates, fleet, ruleSet, cost, distance, budget, startedAt,
                command.effectiveSeed());
    }

    private static List<UUID> orderIdsOf(PlannedRoute route) {
        List<UUID> orderIds = new ArrayList<>();
        for (PlannedStop stop : route.stops()) {
            stop.stop().orderIds().forEach(orderId -> orderIds.add(orderId.value()));
        }
        return orderIds;
    }

    private String strategyOf(RunPlanCommand command) {
        return command.strategy() != null ? command.strategy() : defaultStrategy;
    }
}
