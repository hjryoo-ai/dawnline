package com.dawnline.dispatch.application;

import com.dawnline.common.error.ConflictException;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.dispatch.application.port.in.ReplanRouteUseCase;
import com.dawnline.dispatch.application.port.in.WriteGate;
import com.dawnline.dispatch.application.port.out.DispatchEvents;
import com.dawnline.dispatch.application.port.out.PlannedRouteRepository;
import com.dawnline.dispatch.application.port.out.RouteMutations;
import com.dawnline.dispatch.application.port.out.RouteMutations.PositionedStop;
import com.dawnline.dispatch.application.port.out.RouteMutations.RouteHeader;
import com.dawnline.dispatch.application.port.out.RouteMutations.SettledStop;
import com.dawnline.dispatch.application.port.out.RouteMutations.StopOfOrder;
import com.dawnline.dispatch.application.port.out.RoutePlanRepository;
import com.dawnline.dispatch.application.port.out.RouteSnapshot;
import com.dawnline.dispatch.application.port.out.RuleCatalog;
import com.dawnline.dispatch.application.port.out.VehicleCatalog;
import com.dawnline.dispatch.domain.RoutePlan;
import com.dawnline.dispatch.domain.RouteStopStatus;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 * <h2>읽기 · 계산 · 쓰기 셋이다 (ADR-068)</h2>
 * 계획(ADR-064)과 같은 구조다 — 읽기 전용 트랜잭션에서 읽고, 트랜잭션 없이 {@link RelocateSearch} 를 돌리고, 게이트가 감싼 쓰기
 * 트랜잭션 하나에서 쓴다. 쓰기는 <strong>계산의 전제를 다시 본다</strong>: 옮길 stop 을 잠그고 {@code PLANNED} 인지, 받을 라우트가
 * 아직 끝나지 않았는지(후속 A), 원 · 대상 라우트의 {@code revision} 이 읽기 때와 같은지. 어긋나면 결과를 버리고 {@link Outcome#STALE} 이다. 옮기는 것은 stop
 * <strong>행</strong>이다 — 정정 전에는 주문을 대상의 새 stop 으로 옮기고 원래 행을 지웠고, 그 순간 도착한 배송이 적힐 자리를
 * 잃었다(두 번째 peak-day, 근거: 관측(재현됨) — {@code ReplanRaceIT}).
 *
 * <h2>쿨다운 — 읽기는 비교만, 쓰기는 한 문장</h2>
 * 읽기가 먼저 비교해 쿨다운 안이면 계산하지 않는다(계산을 아낀다). 집는 것은 쓰기의 {@link RouteMutations#tryStartReplan}
 * 한 문장이다 — 멱등 소비자는 이 자리를 대신하지 못한다: 두 at-risk 는 {@code eventId} 가 달라 둘 다 처음 보는 이벤트다
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
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

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
     * @param transactions       읽기 · 쓰기 트랜잭션 (ADR-068 결정 1 — 계산은 둘 사이에서 트랜잭션 없이 돈다)
     */
    public ReplanRouteService(RouteMutations routes, RoutePlanRepository plans,
            PlannedRouteRepository explanations, VehicleCatalog vehicles, RuleCatalog rules,
            DispatchEvents events, DistanceProvider distance, DispatchMetrics metrics, Clock clock,
            Duration cooldown, Duration deviationTolerance, PlatformTransactionManager transactions) {
        this(routes, plans, explanations, vehicles, rules, events, distance, metrics, clock, cooldown,
                deviationTolerance, transactions, RelocateSearch.MAX_EVALUATIONS);
    }

    /** 평가 상한을 준 서비스 — 상한에 걸리는 두 갈래(truncated · searchTruncated)를 작은 픽스처로 보는 테스트가 쓴다. */
    ReplanRouteService(RouteMutations routes, RoutePlanRepository plans,
            PlannedRouteRepository explanations, VehicleCatalog vehicles, RuleCatalog rules,
            DispatchEvents events, DistanceProvider distance, DispatchMetrics metrics, Clock clock,
            Duration cooldown, Duration deviationTolerance, PlatformTransactionManager transactions,
            int maxEvaluations) {

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
        this.search = new RelocateSearch(distance, cost, maxEvaluations);
        Objects.requireNonNull(transactions, "transactions");
        this.reads = new TransactionTemplate(transactions);
        this.reads.setReadOnly(true);
        this.writes = new TransactionTemplate(transactions);
    }

    @Override
    public Optional<Outcome> replan(ReplanCommand command, WriteGate gate) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(gate, "gate");
        Instant now = clock.instant();

        Snapshot snapshot = Objects.requireNonNull(reads.execute(status -> read(command, now)));

        // 여기서부터 쓰기 전까지 트랜잭션이 없다 — 커넥션을 쥐지 않는다 (ADR-068 결정 1). 계산이 PLANNED 로 본 stop 이
        // 이 사이에 끝날 수 있고, 그래서 쓰기가 다시 본다.
        Search input = snapshot.search();
        RelocateSearch.@Nullable Outcome found = input == null ? null
                : search.search(input.ruleSet(), input.source(), input.candidates());

        AtomicReference<Outcome> written = new AtomicReference<>();
        boolean entered = gate.enter(() -> written.set(writes.execute(status ->
                write(command, snapshot, found, now))));
        if (!entered) {
            log.debug("같은 at-risk 를 이미 처리했다 — 계산한 결과를 버린다. routeId={}", command.routeId());
            return Optional.empty();
        }
        Outcome outcome = Objects.requireNonNull(written.get(), "게이트가 들어갔다고 했는데 쓰기가 돌지 않았다");
        // 커밋 뒤에 센다 — 게이트가 돌아왔으면 커밋이 끝났다. 쿨다운에 막힌 쪽은 전에도 견주지 않았다.
        if (snapshot.deviationMismatch() && outcome != Outcome.COOLDOWN) {
            metrics.atRiskDeviationMismatch();
        }
        return Optional.of(outcome);
    }

    /**
     * 읽기 — 읽기 전용 트랜잭션 안. 아무것도 쓰지 않는다(쿨다운도 쓰기가 집는다).
     *
     * <p>예외는 여기서 나간다 — 계획이 없다, 시작 시각 · 캠프 좌표가 없다, 원 라우트의 차량이 없다. 정말로 처리하지 못한 것이고
     * 재시도의 몫이다(클래스 주석 「실패하지 않는다 — 갈린다」).
     */
    private Snapshot read(ReplanCommand command, Instant now) {
        if (routes.coolingDown(command.routeId(), now, cooldown)) {
            log.debug("쿨다운 안이라 재계획하지 않는다. routeId={}", command.routeId());
            return Snapshot.early(Outcome.COOLDOWN, false);
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

        // 앵커는 닿은 stop 이 먼저(더 최근의 사실), 없으면 출발이다(ADR-072). at-risk 는 설계상 출발 지연에서 첫 stop 전에 발화한다 —
        // 출발이 앵커가 아니던 때는 그 자리가 전부 no-anchor 였다(7-4 의 29/32).
        Duration deviation = routes.lastSettledStop(command.routeId()).map(SettledStop::deviation)
                .or(() -> routes.departureDeviation(command.routeId()))
                .orElse(null);
        if (deviation == null) {
            // 편차를 «모른다». 0 으로 두고 돌리면 출발 지연 라우트가 「이득 없음」으로 조용히 닫힌다 — 모름은 0 이 아니다(ADR-048 결정 1,
            // 기각 (8)). 남는 것은 at-risk 가 출발보다 먼저 소비된 창이다(다른 토픽 — ADR-072 결정 4).
            log.debug("닿은 stop 도 출발도 없어 편차를 모른다. routeId={}", command.routeId());
            return Snapshot.early(Outcome.NO_ANCHOR, false);
        }
        boolean mismatch = diverged(command, deviation);

        Map<UUID, VehicleSpec> fleet = fleetOf(plan.campId(), startAt);
        RuleSet ruleSet = rules.forCamp(plan.campId());
        RelocateSearch.RouteInput source = inputOf(header, fleet, depot, startAt,
                deviation);
        if (source.stops().size() == source.frozen()) {
            log.debug("남은 stop 이 없다. routeId={}", command.routeId());
            return Snapshot.early(Outcome.NO_CANDIDATE, mismatch);
        }

        List<RelocateSearch.RouteInput> candidates = candidatesOf(header, fleet, depot, startAt);
        if (candidates.isEmpty()) {
            log.debug("받을 라우트가 없다. routeId={}, planId={}", command.routeId(), plan.id());
            return Snapshot.early(Outcome.NO_CANDIDATE, mismatch);
        }
        return new Snapshot(null, mismatch, new Search(plan, fleet, depot, startAt, ruleSet, source,
                candidates, routes.revisionsOfPlan(plan.id()), deviation));
    }

    /**
     * 쓰기 — 트랜잭션 하나, 게이트 안. 재검증 → 쿨다운 집기 → 옮기기 (ADR-068 결정 2 · 4).
     *
     * <p>재검증이 쿨다운보다 먼저다: 전제가 바뀌어 버린 결과({@link Outcome#STALE})는 쿨다운을 집지 않는다.
     */
    private Outcome write(ReplanCommand command, Snapshot snapshot,
            RelocateSearch.@Nullable Outcome found, Instant now) {

        Search input = snapshot.search();
        if (input == null || found == null) {
            Outcome early = Objects.requireNonNull(snapshot.early(), "계산하지 않은 스냅샷에는 갈래가 있다");
            if (early == Outcome.COOLDOWN) {
                return Outcome.COOLDOWN;
            }
            return claim(command, now) ? early : Outcome.COOLDOWN;
        }
        if (!found.moved()) {
            if (found.truncated()) {
                // 이득이 없는 것이 아니라 다 못 봤다 — 상한이 걸리는 규모라는 사실이 이 줄과 카운터에 남는다(§6.8).
                log.info("평가 상한에 걸려 이동을 찾지 못했다(다 못 봤다). routeId={}, 후보 라우트 {}대",
                        command.routeId(), input.candidates().size());
            } else {
                log.debug("옮겨도 총비용이 줄지 않는다. routeId={}", command.routeId());
            }
            Outcome outcome = found.truncated() ? Outcome.TRUNCATED : Outcome.NO_GAIN;
            return claim(command, now) ? outcome : Outcome.COOLDOWN;
        }

        Map<UUID, UUID> relocations = lockMoves(command, input, found);
        if (relocations == null) {
            return Outcome.STALE;
        }
        if (!claim(command, now)) {
            return Outcome.COOLDOWN;
        }
        apply(input, found, relocations);
        log.info("부분 재계획을 반영했다. routeId={}, 이동 {}건, 절감 {}원, 편차 {}초",
                command.routeId(), found.moves().size(), found.gainKrw(),
                input.deviation().toSeconds());
        return Outcome.APPLIED;
    }

    /** 쿨다운을 한 문장으로 집는다 (ADR-046 결정 3). 비교만 한 읽기 뒤에 다른 at-risk 가 먼저 집었으면 거짓이다. */
    private boolean claim(ReplanCommand command, Instant now) {
        boolean claimed = routes.tryStartReplan(command.routeId(), now, cooldown);
        if (!claimed) {
            log.debug("계산하는 동안 다른 재계획이 쿨다운을 집었다 — 결과를 버린다. routeId={}", command.routeId());
        }
        return claimed;
    }

    /**
     * 옮길 stop 을 잠그고 계산의 전제를 다시 본다 (ADR-068 결정 2).
     *
     * <p>잠그는 순서는 stop → 라우트다(옮길 stop → 받을 라우트의 끝나지 않은 stop 하나 → 라우트 행) — 상태 반영 · 취소 · 재배정이
     * stop 을 먼저 잡는 순서와 같다.
     *
     * @return stop id → 대상 라우트. 전제가 바뀌었으면 {@code null}
     */
    private @Nullable Map<UUID, UUID> lockMoves(ReplanCommand command, Search input,
            RelocateSearch.Outcome found) {

        Map<UUID, UUID> relocations = new LinkedHashMap<>();
        for (RelocateSearch.Move move : found.moves()) {
            UUID stopId = null;
            for (OrderId orderId : move.orderIds()) {
                Optional<StopOfOrder> locked = routes.lockStopOf(move.fromRouteId(), orderId.value());
                if (locked.isEmpty() || locked.get().status() != RouteStopStatus.PLANNED
                        || (stopId != null && !stopId.equals(locked.get().stopId()))) {
                    // 기사가 닿았거나 끝냈다 — 옮기면 그 사실이 새 자리의 PLANNED 에 가려진다. ADR-026 결정 2 의 넷째 분기를
                    // 개정에 넓힌 자리다. 주문이 원 라우트를 떠났거나(재배정) 취소됐어도 계산한 이동은 지금 라우트의 것이 아니다.
                    log.info("계산하는 동안 옮길 stop 이 바뀌었다 — 결과를 버린다. routeId={}, 상태={}",
                            command.routeId(), locked.map(StopOfOrder::status).map(Enum::name).orElse("원 라우트에 없음"));
                    return null;
                }
                stopId = locked.get().stopId();
            }
            relocations.put(Objects.requireNonNull(stopId, "이동에는 주문이 있다"), move.toRouteId());
        }

        for (UUID receiving : new TreeSet<>(relocations.values())) {
            if (!routes.lockUnfinishedStop(receiving)) {
                // 계산하는 동안 받을 라우트의 마지막 stop 이 끝났다 — 기사는 복귀했다. 넣으면 그 stop 은 아무도 가지 않고 그
                // 차량은 비활성화되지 못한다(ADR-068 후속 A). 잡은 stop 은 커밋까지 끝나지 않으므로 라우트도 끝나지 않는다.
                log.info("계산하는 동안 받을 라우트가 끝났다 — 결과를 버린다. routeId={}, 받을 라우트={}",
                        command.routeId(), receiving);
                return null;
            }
        }

        Set<UUID> touched = new TreeSet<>(found.sequences().keySet());
        touched.add(command.routeId());
        Map<UUID, Integer> revisions = routes.lockRevisions(touched);
        for (UUID routeId : touched) {
            if (!Objects.equals(revisions.get(routeId), input.revisions().get(routeId))) {
                // 취소 · 재배정 · 다른 재계획이 순서나 소속을 바꿨다 — 계산한 순서는 지금 라우트의 것이 아니다.
                log.info("계산하는 동안 라우트가 개정됐다 — 결과를 버린다. routeId={}, 바뀐 라우트={}, 읽기={}, 지금={}",
                        command.routeId(), routeId, input.revisions().get(routeId), revisions.get(routeId));
                return null;
            }
        }
        return relocations;
    }

    /**
     * 두 편차를 견준다 — <strong>버리지도 않고 입력으로 쓰지도 않는다</strong>.
     *
     * <p>갈린다는 것은 tracking 과 dispatch 가 같은 라우트를 다르게 보고 있다는 뜻이고, 그
     * 사실이 먼저 필요하다(ADR-048 결정 2). 세는 것은 쓰기가 커밋된 뒤다 — 같은 이벤트의 재전달이 두 번 세지 않는다.
     *
     * @return 허용 오차를 넘게 갈렸으면 참
     */
    private boolean diverged(ReplanCommand command, Duration mine) {
        Duration gap = mine.minus(command.deviation()).abs();
        if (gap.compareTo(deviationTolerance) > 0) {
            log.info("편차가 갈렸다. routeId={}, tracking={}초, dispatch={}초",
                    command.routeId(), command.deviationSeconds(), mine.toSeconds());
            return true;
        }
        return false;
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
     * 같은 계획의 끝나지 않은 다른 라우트들 (§6.8 2단계, ADR-068 후속 A — 끝난 라우트가 받은 stop 은 복귀한 기사의 것이 된다).
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
        for (RouteHeader header : routes.unfinishedRoutesOfPlan(source.planId())) {
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
     * <p>옮기는 것은 stop <strong>행</strong>이다(ADR-068 결정 3) — 주문을 대상의 새 stop 으로 옮기고 원래 행을 지우면 그 행의
     * 락을 기다리던 상태 반영이 0 행을 고치고, 합쳐진 stop 이 주문마다 흩어진다. 저장은 <strong>계획 시계</strong>로 한다 — 평가에
     * 쓴 밀린 시계가 아니다.
     */
    private void apply(Search input, RelocateSearch.Outcome found, Map<UUID, UUID> relocations) {
        relocations.forEach(routes::relocateStop);

        Map<UUID, UUID> vehicleOf = new LinkedHashMap<>();
        List<Explanation> reasons = new ArrayList<>();
        for (RelocateSearch.Move move : found.moves()) {
            UUID vehicleId = vehicleOf.computeIfAbsent(move.toRouteId(),
                    routeId -> routes.findHeader(routeId).orElseThrow().vehicleId());
            move.orderIds().forEach(orderId -> reasons.add(Explanation.relocated(orderId,
                    input.fleet().get(vehicleId).id(), move.fromRouteId(), move.toRouteId(),
                    move.gainKrw(), found.truncated())));
        }

        found.sequences().forEach((routeId, stops) -> republish(input.plan(), routeId, stops,
                input.fleet(), input.depot(), input.startAt(), input.ruleSet()));
        // 설명은 마지막이다 — 라우트가 실제로 옮겨진 뒤에야 「어디서 어디로」가 참이 된다.
        explanations.saveExplanations(input.plan().id(), reasons, Map.of());
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

    /**
     * 읽기 단계가 본 것.
     *
     * @param early             계산하지 않고 끝난 갈래({@code cooldown} · {@code no-anchor} · {@code no-candidate}). 계산하면 null
     * @param deviationMismatch 페이로드의 편차와 갈렸다 — 쓰기가 커밋된 뒤에 센다
     * @param search            계산의 입력이자 쓰기 단계가 대조할 기준. {@code early} 가 있으면 null
     */
    private record Snapshot(@Nullable Outcome early, boolean deviationMismatch, @Nullable Search search) {

        static Snapshot early(Outcome outcome, boolean deviationMismatch) {
            return new Snapshot(outcome, deviationMismatch, null);
        }
    }

    /**
     * 계산의 입력.
     *
     * @param revisions 계획의 라우트마다 읽은 {@code revision} — 쓰기가 대조한다 (ADR-068 결정 2)
     * @param deviation 원 라우트의 편차 — 로그용
     */
    private record Search(RoutePlan plan, Map<UUID, VehicleSpec> fleet, CampDepot depot, Instant startAt,
            RuleSet ruleSet, RelocateSearch.RouteInput source, List<RelocateSearch.RouteInput> candidates,
            Map<UUID, Integer> revisions, Duration deviation) {
    }
}
