package com.dawnline.dispatch.domain.optimizer.strategy;

import com.dawnline.dispatch.domain.optimizer.DispatchStrategy;
import com.dawnline.dispatch.domain.optimizer.Explanation;
import com.dawnline.dispatch.domain.optimizer.Feasibility;
import com.dawnline.dispatch.domain.optimizer.PlanAssembler;
import com.dawnline.dispatch.domain.optimizer.PlanResult;
import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import com.dawnline.dispatch.domain.optimizer.PlannedStop;
import com.dawnline.dispatch.domain.optimizer.PlanningDeadline;
import com.dawnline.dispatch.domain.optimizer.PlanningProblem;
import com.dawnline.dispatch.domain.optimizer.RouteAccumulator;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.StopMerger;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Clarke-Wright savings 로 구성하고 국소 탐색으로 개선한다
 * (DESIGN.md §6.6 {@code savings-cw+ls} · [ADR-042]).
 *
 * <h2>무엇을 재는 전략인가</h2>
 * {@code sweep-greedy-nn+ls} 와 <strong>구성 방식만</strong> 다르다. 스윕은 «먼저 자르고 차에
 * 붙인다» 이고 savings 는 «이어 붙이며 라우트를 키운다» 이다. 뒤의 두 단계 — 미배정 재삽입
 * ([ADR-028])과 국소 탐색([ADR-032]) — 은 <strong>같은 클래스를 그대로 쓴다</strong>. §6.6 이
 * {@code +ls} 를 한 클래스로 둔 것과 같은 이유다: 뒤 단계가 갈라지면 §6.9 의 비교표가 「구성
 * 방식의 차이」가 아니라 「두 구현의 차이」를 재게 된다.
 *
 * <p>공정성의 조건은 셋이고, 셋 다 코드로 지킨다 — <strong>같은 예산</strong>
 * ({@link PlanningDeadline} 은 {@code problem.budget()} 에서 나온다) · <strong>같은 이웃 표</strong>
 * ({@link Neighborhood#DEFAULT_K}) · <strong>같은 재삽입·개선</strong>
 * ({@link UnassignedRepair}, {@link LocalSearchImprover}).
 *
 * <h2>이름에 K 를 넣지 않는다</h2>
 * §6.6 의 이름은 {@code savings-cw+ls} 하나다. 파라미터를 이름에 넣기 시작하면 비교표가
 * 읽히지 않는다 — K 는 측정 문서가 적는다({@code docs/benchmarks/phase4-savings-cw.md}).
 *
 * <h2>차량은 라우트가 다 만들어진 뒤에 붙인다</h2>
 * CW 는 차량 없이 라우트를 만든다(§6.6 의 고전 형태). 그래서 붙이는 단계가 <strong>배정</strong>
 * 이고, 그 자리의 규칙은 스윕과 같다 — 시험 배치로 한계비용을 재고([ADR-031] 의 동률 키),
 * 희소 조합의 좌석은 {@link SeatReservation} 이 막는다([ADR-039]).
 *
 * <p><strong>예약이 배정에만 있으면 늦다</strong>는 것이 이 전략에서 새로 드러난 사실이다 —
 * 라우트가 이미 만들어진 뒤에는 예약이 고칠 것이 없다. 그래서 [ADR-039] 의 집계 불변식을
 * {@link SavingsMerger 구성 단계}로 옮겼다. 여기 남은 것은 <em>그 위에서</em> 「이 차의 이 자리가
 * 이 수요의 것인가」를 묻는 문이다.
 *
 * <h2>차 한 대에 라우트 하나</h2>
 * CW 의 라우트는 그 자체가 «한 차가 갈 만한 것» 이므로 한 대에 하나만 붙인다. 남는 라우트는
 * 미배정으로 내려가고 {@link UnassignedRepair} 가 stop 단위로 다시 본다 — 클러스터를 반으로
 * 쪼개던 자리({@link GreedyAssigner})가 여기서는 재삽입이다.
 */
public final class SavingsClarkeWright implements DispatchStrategy {

    /** 전략 이름 (§6.6). */
    public static final String NAME = "savings-cw+ls";

    /**
     * 붙일 차를 고르는 키 — 스윕의 것과 <strong>같다</strong> (§6.5 3단계, [ADR-031]).
     *
     * <p>많이 싣는 쪽 → 한계비용 → 능력이 적은 차. 차가 비어 있으므로 한계비용은 곧 이 라우트
     * 전체의 비용이다.
     */
    private static final Comparator<Trial> BEST = Comparator.comparingInt(Trial::leftover)
            .thenComparingLong(Trial::marginalKrw)
            .thenComparing(Trial::vehicle, VehicleSpec.LEAST_CAPABLE_FIRST);

    private final LocalSearchImprover improver = new LocalSearchImprover();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PlanResult plan(PlanningProblem problem) {
        PlanningDeadline deadline = PlanningDeadline.from(problem.budget());
        List<Stop> stops = StopMerger.merge(problem.candidates());

        // 좌석 예약은 스윕과 같은 스냅샷이다 (§6.3, [ADR-039]) — 통합 후 조합을 계획 시작
        // 시점에 한 번 센다. 구성 단계는 이 표의 <em>집계</em>를 쓰고, 배정 단계는 문을 쓴다.
        SeatReservation seats =
                SeatReservation.of(stops, problem.vehicles(), problem.rules().routeStopCap());

        List<List<Stop>> constructed = SavingsMerger.merge(problem, stops, deadline);

        List<RouteAccumulator> routes = problem.vehicles().stream()
                .map(vehicle -> new RouteAccumulator(problem.rules(), vehicle, problem.depot(),
                        problem.distance(), problem.startedAt()))
                .toList();

        Map<Stop, Feasibility> refusals = new LinkedHashMap<>();
        List<Stop> unassigned = attach(problem, constructed, routes, seats, refusals, deadline);

        UnassignedRepair.Outcome repaired =
                UnassignedRepair.repair(problem, routes, unassigned, refusals, deadline);
        List<RouteAccumulator> finished = repaired.routes();
        unassigned = repaired.unassigned();

        boolean improvementCut = false;
        if (problem.runsImprovement()) {
            LocalSearchImprover.Outcome improved =
                    improver.improve(problem, finished, deadline.elapsedNanos());
            finished = improved.routes();
            improvementCut = improved.budgetExhausted();
            // 개선 단계는 자리를 만든다 — 라우트가 짧아지면 근무창·약속창에 여유가 생긴다.
            UnassignedRepair.Outcome again =
                    UnassignedRepair.repair(problem, finished, unassigned, refusals, deadline);
            finished = again.routes();
            unassigned = again.unassigned();
        }

        List<PlannedRoute> planned = new ArrayList<>();
        List<Explanation> explanations = new ArrayList<>();
        for (int i = 0; i < finished.size(); i++) {
            RouteAccumulator route = finished.get(i);
            if (route.isEmpty()) {
                continue;                       // 빈 라우트는 만들지 않는다 (고정비를 물지 않는다)
            }
            PlannedRoute result = route.toRoute(problem.cost());
            planned.add(result);
            VehicleSpec vehicle = problem.vehicles().get(i);
            for (PlannedStop stop : result.stops()) {
                boolean blocked = seats.blocked(stop.stop());
                stop.stop().orderIds().forEach(orderId -> explanations.add(blocked
                        ? Explanation.assignedElsewhere(orderId, vehicle.id(), 0L,
                                SeatReservation.RESERVED)
                        : Explanation.assigned(orderId, vehicle.id(), 0L)));
            }
        }

        return PlanAssembler.assemble(problem, planned, unassigned, refusals, explanations,
                deadline.hit() || improvementCut);
    }

    /**
     * 만들어진 라우트를 차량에 붙인다.
     *
     * <p>순서는 <strong>가장 이른 약속 마감</strong>부터다 — §6.5 3단계가 클러스터를 그 순서로
     * 배정하는 것과 같은 이유이고(시간이 급한 것부터 자리를 잡아야 지각이 준다), 마지막 키가
     * 주문 id 인 것은 재현성 때문이다(불변규칙 12).
     */
    private List<Stop> attach(PlanningProblem problem, List<List<Stop>> constructed,
            List<RouteAccumulator> routes, SeatReservation seats,
            Map<Stop, Feasibility> refusals, PlanningDeadline deadline) {

        List<List<Stop>> ordered = new ArrayList<>(constructed);
        ordered.sort(Comparator.comparing(SavingsClarkeWright::earliestDeadline)
                .thenComparing(route -> route.getFirst().orderIds().getFirst().value()));

        boolean[] taken = new boolean[routes.size()];
        List<Stop> unassigned = new ArrayList<>();
        for (List<Stop> route : ordered) {
            if (deadline.expired()) {
                // 「실을 차가 없다」가 아니라 「시도하지 못했다」다 ([ADR-036]).
                route.forEach(stop -> refusals.put(stop, GreedyAssigner.DEADLINE));
                unassigned.addAll(route);
                continue;
            }
            unassigned.addAll(place(problem, route, routes, taken, seats));
        }
        unassigned.forEach(stop ->
                refusals.putIfAbsent(stop, GreedyAssigner.lastRefusalFor(stop, routes)));
        return List.copyOf(unassigned);
    }

    /**
     * 이 라우트를 <strong>아직 비어 있는</strong> 차 하나에 붙인다.
     *
     * <p>재는 방법은 스윕과 같다 — 사본에 실제로 실어 보고 비용을 본다({@link RouteAccumulator#branch()}).
     * 한 stop 도 못 실은 차는 후보가 아니고, 아무 차도 받지 못하면 라우트 전체가 재삽입으로 내려간다.
     *
     * @return 이 라우트에서 싣지 못한 stop 들
     */
    private List<Stop> place(PlanningProblem problem, List<Stop> route,
            List<RouteAccumulator> routes, boolean[] taken, SeatReservation seats) {

        Trial best = null;
        int bestIndex = -1;
        for (int v = 0; v < routes.size(); v++) {
            if (taken[v]) {
                continue;
            }
            RouteAccumulator target = routes.get(v);
            RouteAccumulator trial = target.branch();
            List<Stop> leftover = appendInOrder(trial, route, seats.gateFor(target.state()));
            if (leftover.size() == route.size()) {
                continue;                       // 한 개도 못 넣었다 — 이 차는 후보가 아니다
            }
            Trial candidate = new Trial(leftover.size(),
                    trial.toRoute(problem.cost()).cost().krw(), target.state().vehicle());
            if (best == null || BEST.compare(candidate, best) < 0) {
                best = candidate;
                bestIndex = v;
            }
        }
        if (bestIndex < 0) {
            return route;
        }
        RouteAccumulator target = routes.get(bestIndex);
        taken[bestIndex] = true;
        return appendInOrder(target, route, seats.gateFor(target.state()));
    }

    /**
     * CW 가 만든 <strong>순서 그대로</strong> 싣는다. 못 싣는 stop 은 건너뛰고 남긴다.
     *
     * <p>여기서 다시 시퀀싱하지 않는 이유는 그것이 §6.5 4단계의 일이기 때문이다 — 구성 단계가
     * 정한 순서를 배정이 덮어쓰면 비교표가 다시 두 가지를 섞는다. 순서를 고치는 것은 5단계다.
     */
    private static List<Stop> appendInOrder(RouteAccumulator route, List<Stop> stops,
            SeatGate seats) {

        List<Stop> leftover = new ArrayList<>();
        for (Stop stop : stops) {
            // 룰은 「이 차가 실을 수 있는가」이고 문은 「이 자리가 이 수요의 것인가」다 — 따로
            // 묻는다([ADR-039]).
            if (!route.check(stop).feasible() || !seats.admits(stop).feasible()) {
                leftover.add(stop);
                continue;
            }
            route.append(stop);
            seats.seat(stop);
        }
        return leftover;
    }

    private static Instant earliestDeadline(List<Stop> route) {
        return route.stream().map(stop -> stop.promised().end())
                .min(Comparator.naturalOrder()).orElseThrow();
    }

    /**
     * 시험 배치 결과.
     *
     * @param leftover    넣지 못한 stop 수
     * @param marginalKrw 이 차로 이 라우트를 굴리는 비용. 차가 비어 있으므로 곧 한계비용이다
     * @param vehicle     이 사본의 차량. 동률을 깨는 데 쓴다 ([ADR-031])
     */
    private record Trial(int leftover, long marginalKrw, VehicleSpec vehicle) {
    }
}
