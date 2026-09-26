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
import com.dawnline.dispatch.domain.PlanModeSelector;
import com.dawnline.dispatch.domain.PlanStatus;
import com.dawnline.dispatch.domain.RoutePlan;
import com.dawnline.dispatch.domain.optimizer.Candidate;
import com.dawnline.common.GeoPoint;
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
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 웨이브 하나를 계획하고 발행한다 (DESIGN.md §5.3, §6.5).
 *
 * <h2>읽기 · 계산 · 쓰기 — 계산은 트랜잭션 밖이다 (ADR-064)</h2>
 * <ol>
 *   <li><strong>읽기</strong> — 읽기 전용 트랜잭션 하나. 기존 계획 · 모드 판단 · 후보 · 룰 · 차량. 아무것도 쓰지 않는다.</li>
 *   <li><strong>계산</strong> — 트랜잭션 없음. 최적화와 하드 룰 검증. {@code peak} 한 번이 19.9초이고, 그동안 커넥션을 쥐면
 *       컷오프 직전 버스트(§8.2)에 풀이 그만큼 준다. 정정 전에는 쥐고 있었다(근거: 관측(재현됨), {@code PlanComputeConnectionIT}).</li>
 *   <li><strong>쓰기</strong> — 게이트 안의 트랜잭션 하나. 계획 저장·후보 상태 전이·세 이벤트의 outbox 적재가 <strong>모두 같은
 *       트랜잭션</strong>이다. 나눠 넣으면 "완료라는데 라우트가 없다" 가 생긴다(ADR-024). {@code PLANNING} 은 커밋되지 않는다.</li>
 * </ol>
 * 원래 판단은 「{@code large} 674 ms 라 나누는 복잡도가 더 비싸다 — 재검토 지점은 예산(30초)에 가까워질 때」였고 {@code peak} 이
 * 그 지점에 닿았다. 읽기와 쓰기가 한 트랜잭션이 아니게 되어 잃는 것은 없다 — {@code READ COMMITTED} 라 원래도 문장마다 새 스냅샷이었다.
 *
 * <h2>모드는 계획마다 다시 정한다</h2>
 * §6.7 의 열화는 <strong>래치가 아니다</strong>. {@link PlanModeSelector} 가 매번 두 사실을
 * 다시 본다 — 이 파티션이 얼마나 밀렸는가(레코드가 싣고 온다), 같은 캠프의 직전 계획이 예산을
 * 얼마나 썼는가(DB 가 답한다). 그래서 "한 번 열화하면 누가 되돌리는가" 라는 질문이 없다.
 *
 * <p>그리고 <strong>사다리</strong>다 — 둘은 같은 처방을 내지 않는다. 랙만 FAST 로 보내고,
 * 예산 조건은 다음 계획의 <em>개선 예산</em>만 줄인다({@code budgetFactor}).
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
    private final DistanceProvider distance;
    private final PlanValidator validator = new PlanValidator();
    private final CostModel cost = new CostModel();
    private final DispatchMetrics metrics;
    private final Clock clock;
    private final String defaultStrategy;
    private final PlanningBudget budget;
    private final PlanModeSelector modeSelector;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    /**
     * @param plans           계획 저장소
     * @param candidates      후보 저장소
     * @param routes          라우트·설명 저장소
     * @param events          발행 (Outbox)
     * @param vehicles        차량 카탈로그
     * @param rules           룰 카탈로그
     * @param distance        거리 제공자
     * @param metrics         §9.1 계획 메트릭
     * @param clock           시각 출처 (불변규칙 12)
     * @param defaultStrategy 기본 전략 (§6.6)
     * @param budget          시간 예산 (§6.7)
     * @param modeSelector    열화 판단 (§6.7, ADR-034)
     * @param transactions    읽기(읽기 전용)와 쓰기 트랜잭션 — 계산은 둘 사이에서 트랜잭션 없이 돈다 (ADR-064)
     */
    public RunPlanService(RoutePlanRepository plans, DispatchCandidateRepository candidates,
            PlannedRouteRepository routes, DispatchEvents events, VehicleCatalog vehicles,
            RuleCatalog rules, DistanceProvider distance, DispatchMetrics metrics, Clock clock,
            String defaultStrategy, PlanningBudget budget, PlanModeSelector modeSelector,
            PlatformTransactionManager transactions) {

        this.plans = Objects.requireNonNull(plans, "plans");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.events = Objects.requireNonNull(events, "events");
        this.vehicles = Objects.requireNonNull(vehicles, "vehicles");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.distance = Objects.requireNonNull(distance, "distance");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.defaultStrategy = Objects.requireNonNull(defaultStrategy, "defaultStrategy");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.modeSelector = Objects.requireNonNull(modeSelector, "modeSelector");
        Objects.requireNonNull(transactions, "transactions");
        this.reads = new TransactionTemplate(transactions);
        this.reads.setReadOnly(true);
        this.writes = new TransactionTemplate(transactions);
    }

    @Override
    public Outcome run(RunPlanCommand command, WriteGate gate) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(gate, "gate");
        Instant startedAt = clock.instant();

        Snapshot snapshot = Objects.requireNonNull(reads.execute(status -> read(command, startedAt)));
        if (snapshot.alreadyPublished()) {
            // wave.closed 중복 도착. 멱등 소비자와 wave_id UNIQUE 로 두 겹이다 (§5.3). 계산하지 않고 끝내되 게이트는
            // 지난다 — 받은 이벤트는 한 번씩 게이트를 지나야 멱등 기록과 소비 카운터가 이벤트 수와 맞는다.
            log.debug("이미 발행된 웨이브입니다: waveId={}", command.waveId());
            return gate.enter(() -> { }) ? Outcome.ALREADY_PUBLISHED : Outcome.DUPLICATE;
        }

        // 여기서부터 쓰기 전까지 트랜잭션이 없다 — 커넥션을 쥐지 않는다 (ADR-064).
        @Nullable Computed computed = snapshot.plannable().isEmpty() ? null : compute(command, snapshot, startedAt);

        AtomicReference<Written> written = new AtomicReference<>();
        boolean entered = gate.enter(() -> written.set(writes.execute(status ->
                write(command, snapshot, computed, startedAt))));
        if (!entered) {
            log.info("같은 이벤트를 이미 처리했습니다 — 계산한 결과를 버립니다: waveId={}", command.waveId());
            return Outcome.DUPLICATE;
        }
        Written result = Objects.requireNonNull(written.get(), "게이트가 들어갔다고 했는데 쓰기가 돌지 않았다");
        // 카운터는 커밋 뒤에 센다 — 게이트가 돌아왔으면 커밋이 끝났다 (ADR-064 결정 5, CLAUDE.md).
        if (result.published() != null) {
            metrics.planPublished(result.published(), result.budgetExhausted());
            metrics.planPersisted(result.published().campId(), result.persisted());
        }
        return result.outcome();
    }

    /** 읽기 — 읽기 전용 트랜잭션 안. 아무것도 쓰지 않는다(계획 행도 쓰기 단계가 넣는다). */
    private Snapshot read(RunPlanCommand command, Instant startedAt) {
        Optional<RoutePlan> existing = plans.findByWaveId(command.waveId());
        if (existing.isPresent() && existing.get().status().isTerminal()) {
            return Snapshot.published();
        }
        // 좌표는 계획 행에 저장돼 있다 — 재실행·부분 재계획은 wave.closed 를 다시 받지 않는다(V2 마이그레이션 주석).
        GeoPoint depot = existing.isPresent()
                ? existing.get().depot().orElseThrow(() -> new IllegalStateException(
                        "캠프 좌표가 없는 계획은 다시 돌릴 수 없습니다: planId=" + existing.get().id()))
                : Objects.requireNonNull(command.depot(),
                        "새 계획에는 캠프 좌표가 필요합니다 — wave.closed 의 depot 스냅샷입니다");

        PlanModeSelector.Decision mode = chooseMode(command);
        List<DispatchCandidate> plannable = candidates.findPlannableInWave(command.waveId());
        if (plannable.isEmpty()) {
            return new Snapshot(false, depot, mode, plannable, RuleSet.empty(), List.of());
        }
        List<VehicleSpec> fleet = vehicles.availableAt(command.campId(), startedAt);
        if (fleet.isEmpty()) {
            throw new IllegalStateException("캠프에 가용 차량이 없습니다: " + command.campId());
        }
        return new Snapshot(false, depot, mode, plannable, rules.forCamp(command.campId()), fleet);
    }

    /** 계산 — 트랜잭션 없음. 순수 함수와 하드 룰 검증뿐이다. */
    private Computed compute(RunPlanCommand command, Snapshot snapshot, Instant startedAt) {
        PlanningProblem problem = problemOf(command, snapshot, startedAt);
        PlanResult result = DispatchStrategies.create(strategyOf(command)).plan(problem);
        return new Computed(result, validator.validate(problem, result));
    }

    /**
     * 쓰기 — 게이트 안의 트랜잭션 하나. 계산하는 동안 바뀐 것을 여기서 다시 읽는다: 계획 행(다른 경로가 같은 웨이브를 발행했나)과
     * 취소(ADR-026 분기 2).
     */
    private Written write(RunPlanCommand command, Snapshot snapshot, @Nullable Computed computed,
            Instant startedAt) {

        RoutePlan plan = openPlan(command, snapshot.depot());
        if (plan.status().isTerminal()) {
            // 계산하는 동안 다른 경로(다른 eventId 의 wave.closed · 운영자 재실행)가 발행했다 — wave_id UNIQUE 가 받았다.
            log.info("계산하는 동안 발행된 웨이브입니다 — 결과를 버립니다: waveId={}", command.waveId());
            return Written.of(Outcome.ALREADY_PUBLISHED);
        }
        PlanModeSelector.Decision mode = snapshot.mode();

        if (computed == null) {
            plan.begin(strategyOf(command), mode.mode(), mode.reason(), command.effectiveSeed(), 0,
                    startedAt);
            plan.fail(NO_CANDIDATES, clock.instant());
            plans.update(plan);
            events.planFailed(plan);
            return Written.of(Outcome.NO_CANDIDATES);
        }

        plan.begin(strategyOf(command), mode.mode(), mode.reason(), command.effectiveSeed(),
                snapshot.ruleSet().version(), startedAt);
        plans.update(plan);

        List<PlanValidator.Violation> violations = computed.violations();
        if (!violations.isEmpty()) {
            // 하드 룰을 어긴 계획은 데이터 문제가 아니라 코드 버그다 (§6.5 6단계).
            log.error("계획이 하드 룰을 어겼습니다: waveId={} 위반={}건 첫 위반={}",
                    command.waveId(), violations.size(), violations.getFirst().feasibility());
            plan.fail(RULE_VIOLATION, clock.instant());
            plans.update(plan);
            events.planFailed(plan);
            return Written.of(Outcome.FAILED);
        }

        // 계획 중에 취소된 주문을 뺀다 (ADR-026 분기 2) — revision 없이 닫는 유일한 창이다. 그 창은 이제 읽기가 끝난
        // 때부터 여기까지다(계산 전체).
        PlanResult result = PlanPruner.prune(computed.result(),
                cancelledSince(command.waveId(), snapshot.plannable()));

        if (result.routes().isEmpty()) {
            plan.fail(NO_CANDIDATES, clock.instant());
            plans.update(plan);
            events.planFailed(plan);
            return Written.of(Outcome.FAILED);
        }

        return publish(plan, result, startedAt);
    }

    /**
     * 이 계획을 어떤 모드로 돌릴지 (§6.7, ADR-034).
     *
     * <p>사실 둘의 출처가 다르다 — 랙은 <strong>부르는 쪽</strong>이 싣고 오고(Kafka 어댑터만
     * 안다), 직전 계획 시간은 <strong>DB</strong> 가 답한다. 유스케이스는 둘을 모으기만 하고
     * 판단은 도메인({@link PlanModeSelector})이 한다.
     */
    private PlanModeSelector.Decision chooseMode(RunPlanCommand command) {
        Duration last = plans.lastPublishedDuration(command.campId()).orElse(null);
        PlanModeSelector.Decision decision =
                modeSelector.select(command.mode(), command.backlog(), last, budget.total());

        if (decision.reason().isDegraded()) {
            // 열화는 조용하면 안 된다 — 무엇을 포기했는지 로그와 메트릭 둘 다에 남는다(§6.7).
            log.warn("열화합니다: waveId={} campId={} 사유={} 모드={} 개선예산×{} 파티션랙={} 직전계획={}ms",
                    command.waveId(), command.campId(), decision.reason(), decision.mode(),
                    decision.budgetFactor(), command.backlog(),
                    last == null ? null : last.toMillis());
        }
        return decision;
    }

    private Written publish(RoutePlan plan, PlanResult result, Instant startedAt) {
        Instant finishedAt = clock.instant();
        int durationMs = (int) Duration.between(startedAt, finishedAt).toMillis();
        plan.complete(result.totalCost(), result.assignedOrderCount(), result.unassigned().size(),
                durationMs, finishedAt);

        // 여기부터가 영속화다 — 알고리즘이 아니라 I/O 이고, §6.7 의 30초와 견주지 않는다
        // (ADR-029). 나노초로 재는 이유는 clock 이 저장 정밀도(마이크로초)로 잘려 있어서다.
        long persistFrom = System.nanoTime();

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
        // 메트릭은 여기서 올리지 않는다 — 아직 커밋 전이다. 값만 들고 나가 게이트가 돌아온 뒤에 센다 (ADR-064 결정 5).
        return new Written(Outcome.PUBLISHED, plan, result.budgetExhausted(),
                Duration.ofNanos(System.nanoTime() - persistFrom));
    }

    /**
     * 계획 결과를 후보 상태에 반영한다. 늦게 온 취소는 축 규칙이 지켜 준다.
     *
     * <p><strong>집합 둘, 문장 둘이다</strong>(ADR-029). 계획은 후보를 하나씩 다루지 않는다 —
     * 배정된 것 전부와 미배정된 것 전부다. 한 건씩 {@code findById}→{@code update} 하면 그
     * 5,000개가 영속성 컨텍스트에 남아 이후의 모든 네이티브 질의를 전수 더티 체크로 만든다.
     */
    private void markCandidates(PlanResult result, Instant at) {
        Set<UUID> assigned = new LinkedHashSet<>();
        result.routes().forEach(route -> route.stops().forEach(stop ->
                stop.stop().orderIds().forEach(orderId -> assigned.add(orderId.value()))));
        candidates.recordPlanResult(assigned, CandidateStatus.PLANNED, at);

        Set<UUID> unassigned = new LinkedHashSet<>();
        result.unassigned().forEach(entry -> unassigned.add(entry.orderId().value()));
        candidates.recordPlanResult(unassigned, CandidateStatus.UNASSIGNED, at);
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

    /** 있으면 그것, 없으면 새로 만든다. {@code wave_id} UNIQUE 가 경합을 흡수한다. 쓰기 트랜잭션 안에서만 부른다. */
    private RoutePlan openPlan(RunPlanCommand command, GeoPoint depot) {
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
        RoutePlan plan = RoutePlan.request(Ids.newId(), command.waveId(), command.campId(), depot);
        if (!plans.insertIfAbsent(plan)) {
            return plans.findByWaveId(command.waveId()).orElseThrow(() ->
                    new IllegalStateException("계획을 넣지도 찾지도 못했습니다: " + command.waveId()));
        }
        return plan;
    }

    private PlanningProblem problemOf(RunPlanCommand command, Snapshot snapshot, Instant startedAt) {
        List<Candidate> optimizerCandidates = new ArrayList<>(snapshot.plannable().size());
        for (DispatchCandidate candidate : snapshot.plannable()) {
            optimizerCandidates.add(new Candidate(OrderId.of(candidate.orderId()),
                    candidate.location(),
                    new Parcel(candidate.weightG(), candidate.volumeCm3(),
                            candidate.requiresCold(), candidate.hazmat()),
                    candidate.promised(), candidate.serviceSeconds(), candidate.priority()));
        }
        PlanModeSelector.Decision mode = snapshot.mode();
        return new PlanningProblem(
                new WaveRef(command.waveId(), command.campId(), "SAME_DAY", startedAt),
                new CampDepot(command.campId(), snapshot.depot()), optimizerCandidates, snapshot.fleet(),
                snapshot.ruleSet(), cost, distance, budget, mode.mode(), mode.budgetFactor(), startedAt,
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

    /**
     * 읽기 단계가 모은 것. {@code alreadyPublished} 면 나머지는 비어 있다.
     *
     * @param alreadyPublished 이미 발행된 웨이브 — 계산하지 않는다
     * @param depot            캠프 좌표 (기존 계획의 것이 명령의 것을 이긴다 — 재실행 경로)
     * @param mode             모드 판단
     * @param plannable        계획할 후보. 비면 계산하지 않는다
     * @param ruleSet          룰셋
     * @param fleet            가용 차량
     */
    private record Snapshot(boolean alreadyPublished, @Nullable GeoPoint depot,
            PlanModeSelector.@Nullable Decision mode, List<DispatchCandidate> plannable, RuleSet ruleSet,
            List<VehicleSpec> fleet) {

        static Snapshot published() {
            return new Snapshot(true, null, null, List.of(), RuleSet.empty(), List.of());
        }

        @Override
        public GeoPoint depot() {
            return Objects.requireNonNull(depot, "발행된 웨이브의 스냅샷에는 좌표가 없다");
        }

        @Override
        public PlanModeSelector.Decision mode() {
            return Objects.requireNonNull(mode, "발행된 웨이브의 스냅샷에는 모드가 없다");
        }
    }

    /** 계산 단계의 결과. */
    private record Computed(PlanResult result, List<PlanValidator.Violation> violations) {
    }

    /**
     * 쓰기 단계의 결과 — 발행했으면 커밋 뒤에 셀 값을 함께 든다.
     *
     * @param outcome         결과
     * @param published       발행한 계획. 발행하지 않았으면 {@code null}
     * @param budgetExhausted 계획이 예산에 잘렸나
     * @param persisted       영속화 시간 (ADR-029)
     */
    private record Written(Outcome outcome, @Nullable RoutePlan published, boolean budgetExhausted,
            Duration persisted) {

        static Written of(Outcome outcome) {
            return new Written(outcome, null, false, Duration.ZERO);
        }
    }
}
