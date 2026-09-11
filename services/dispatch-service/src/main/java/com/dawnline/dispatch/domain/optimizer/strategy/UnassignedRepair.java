package com.dawnline.dispatch.domain.optimizer.strategy;

import com.dawnline.common.GeoPoint;
import com.dawnline.dispatch.domain.optimizer.Feasibility;
import com.dawnline.dispatch.domain.optimizer.PlannedStop;
import com.dawnline.dispatch.domain.optimizer.PlanningDeadline;
import com.dawnline.dispatch.domain.optimizer.PlanningProblem;
import com.dawnline.dispatch.domain.optimizer.RouteAccumulator;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 미배정 정책 — <strong>누가 빠지고, 누가 다시 들어오는가</strong> (DESIGN.md §6.5 3단계, ADR-028).
 *
 * <h2>부재하는 규칙이 우연을 부른다</h2>
 * §6.5 3단계의 "그래도 없으면 미배정" 은 <em>어느</em> 주문을 남길지를 말하지 않았다. 말하지
 * 않으면 아무도 안 정한 것이 아니라 <strong>우연이 정한다</strong> — 지금까지는 마지막 클러스터에
 * 남은 주문이 그대로 떨어졌다. 측정이 그것을 드러냈다: {@code small} 에서 두 전략의 미배정
 * 건수가 <strong>9 로 같은데 페널티는 20,000원 달랐다.</strong> 건수가 같고 값이 다르면 남긴
 * 대상이 다르다는 뜻이다.
 *
 * <h2>규칙은 목적함수를 그대로 따른다</h2>
 * §6.1 은 이미 답을 갖고 있다 — 싣지 않으면 {@code UNASSIGNED_PENALTY} 를 물고, 실으면 라우트
 * 비용이 오른다. 그러니
 *
 * <pre>
 * 비싼 것부터 자리를 준다. 그리고 오르는 비용이 페널티보다 쌀 때만 싣는다.
 * </pre>
 *
 * 이 한 문장이 <strong>둘 다</strong>이다. "누가 빠지는가" 는 <em>끝까지 자리를 못 찾은 쪽</em>
 * 이고, 그건 페널티가 싼 것들이다. 두 질문을 두 곳에 적으면 같은 정책이 두 벌이 된다.
 *
 * <h2>두 번 부른다</h2>
 * 탐욕 배정 직후 한 번, 국소 탐색 뒤에 한 번. 두 번째가 필요한 이유는 개선 단계가
 * <strong>자리를 만들기 때문</strong>이다 — 라우트가 짧아지면 근무창과 약속창에 여유가 생기고,
 * 첫 번째에 못 들어간 stop 이 들어갈 수 있다. 재삽입 자체는 개선 단계가 아니라 <em>값싼 탐욕</em>
 * 이라 열화 모드(§6.7 FAST)에서도 돈다 — FAST 가 생략하는 것은 국소 탐색이다.
 *
 * <h2>결정적이다</h2>
 * 정렬 키에 주문 id 를 마지막으로 두고(불변규칙 12), 라우트는 인덱스 순, 자리는 앞에서부터
 * 보고, 동률에서는 먼저 만난 것이 이긴다.
 */
final class UnassignedRepair {

    /** 라우트 하나에서 정확히 재 볼 삽입 자리 수 (거리가 가까운 순). */
    private static final int TRIED_POSITIONS = 3;

    /** 마감 때문에 시도하지 못한 stop 의 사유 ([ADR-036]). */
    private static final Feasibility DEADLINE = Feasibility.violated(
            "plan-deadline", "계획 마감 시간이 지나 재삽입을 시도하지 못했습니다");

    private UnassignedRepair() {
    }

    /**
     * 결과.
     *
     * @param routes     재삽입이 반영된 라우트들 (입력과 같은 순서·같은 길이)
     * @param unassigned 끝까지 자리를 못 찾은 stop 들 — <strong>페널티가 싼 쪽</strong>이다
     * @param inserted   실제로 실은 stop 수
     */
    record Outcome(List<RouteAccumulator> routes, List<Stop> unassigned, int inserted) {
    }

    /**
     * 미배정을 다시 싣는다.
     *
     * @param problem    계획 입력
     * @param seeded     차량 순서대로의 라우트들 (빈 것 포함)
     * @param unassigned 아직 배정되지 못한 stop 들
     * @param refusals   stop 별 불가 사유. 마감에 잘린 stop 의 사유를 여기 적는다
     * @param deadline   계획 전체의 마감 ([ADR-036])
     */
    static Outcome repair(PlanningProblem problem, List<RouteAccumulator> seeded,
            List<Stop> unassigned, Map<Stop, Feasibility> refusals, PlanningDeadline deadline) {

        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(seeded, "seeded");
        Objects.requireNonNull(deadline, "deadline");
        if (unassigned.isEmpty()) {
            return new Outcome(List.copyOf(seeded), List.of(), 0);
        }

        List<VehicleSpec> vehicles = seeded.stream().map(route -> route.state().vehicle()).toList();
        List<List<Stop>> plan = new ArrayList<>(seeded.size());
        long[] costs = new long[seeded.size()];
        for (int r = 0; r < seeded.size(); r++) {
            List<Stop> stops = new ArrayList<>();
            for (PlannedStop planned : seeded.get(r).state().stops()) {
                stops.add(planned.stop());
            }
            plan.add(stops);
            costs[r] = RouteRebuild.cost(problem, vehicles.get(r), stops);
        }

        List<Stop> queue = new ArrayList<>(unassigned);
        queue.sort(byPenaltyDescending(problem));

        // 라우트의 <strong>현재 상태</strong>. 위치 무관 하드 룰이 이것을 본다 ([ADR-037]).
        List<RouteAccumulator> live = new ArrayList<>(seeded);

        List<Stop> left = new ArrayList<>();
        int inserted = 0;
        for (Stop stop : queue) {
            if (deadline.expired()) {
                // 재삽입은 값싼 탐욕이지만 «싸다» 는 실행 가능한 문제에서만 참이다 —
                // 미배정이 많으면 이 루프가 계획 시간의 61%가 된다([ADR-036] 의 측정).
                left.add(stop);
                refusals.put(stop, DEADLINE);
                continue;
            }
            // 실으면 오르는 비용이 안 실었을 때 무는 페널티보다 싸야 한다 (§6.1 목적함수).
            // 상한을 페널티로 두는 것이 곧 그 조건이다.
            long budget = problem.rules().unassignedPenalty(stop).krw();
            Placement best = null;
            Feasibility refused = null;
            for (int r = 0; r < plan.size(); r++) {
                // 「이 stop 이 이 라우트에 들어갈 수 있기는 한가」 — stop 수·적재·차량 속성은
                // <strong>넣는 자리와 무관</strong>하므로, 여기서 거절이면 어느 자리도 볼 필요가
                // 없다 ([ADR-037]). 시도해도 못 들어가는 자리를 시도하지 않는 것이라
                // <strong>결과가 바뀔 수 없다.</strong>
                Feasibility admits = problem.rules()
                        .checkPositionIndependent(stop, vehicles.get(r), live.get(r).state());
                if (!admits.feasible()) {
                    refused = admits;
                    continue;
                }
                for (int at : nearestPositions(problem, plan.get(r), stop)) {
                    List<Stop> candidate = new ArrayList<>(plan.get(r));
                    candidate.add(at, stop);
                    long cost = RouteRebuild.cost(problem, vehicles.get(r), candidate);
                    if (cost == RouteRebuild.INFEASIBLE) {
                        continue;
                    }
                    long delta = cost - costs[r];
                    if (delta < budget) {
                        best = new Placement(r, candidate, cost);
                        budget = delta;         // 더 싼 자리만 이긴다 — 동률은 먼저 만난 쪽
                    }
                }
            }
            if (best == null) {
                // 어느 라우트도 받지 못했다. 위치 무관 룰이 거절한 사유가 있으면 그것을 남긴다 —
                // 「실을 차가 없다」보다 「용량 초과」·「stop 상한」이 §6.3 에 쓸모 있는 답이다.
                left.add(stop);
                if (refused != null) {
                    refusals.putIfAbsent(stop, refused);
                }
                continue;
            }
            plan.set(best.route(), best.stops());
            costs[best.route()] = best.cost();
            live.set(best.route(),
                    RouteRebuild.accumulate(problem, vehicles.get(best.route()), best.stops()));
            inserted++;
        }

        List<RouteAccumulator> built = new ArrayList<>(plan.size());
        for (int r = 0; r < plan.size(); r++) {
            RouteAccumulator route = RouteRebuild.accumulate(problem, vehicles.get(r), plan.get(r));
            if (route == null) {
                // 받아들인 삽입은 전부 실행 가능했다. 여기서 걸리면 재삽입 코드의 버그다.
                throw new IllegalStateException("재삽입한 라우트가 하드 룰을 어깁니다: 차량 "
                        + vehicles.get(r).id());
            }
            built.add(route);
        }
        return new Outcome(built, List.copyOf(left), inserted);
    }

    /**
     * 비싼 것부터. 동률은 주문 수가 많은 쪽(통합 stop 하나를 못 실으면 그 안이 전부 미배정이다),
     * 그다음은 주문 id — <strong>마지막 키가 id 인 것은 재현성 때문이다</strong>(불변규칙 12).
     */
    private static Comparator<Stop> byPenaltyDescending(PlanningProblem problem) {
        return Comparator
                .comparingLong((Stop stop) -> problem.rules().unassignedPenalty(stop).krw())
                .thenComparingInt(Stop::orderCount)
                .reversed()
                .thenComparing(stop -> stop.orderIds().getFirst().value());
    }

    /**
     * 이 stop 을 끼울 만한 자리 몇 개 (늘어나는 거리가 작은 순).
     *
     * <p>자리마다 룰을 돌리면 라우트 하나에 n+1 번 재구성이다. 늘어나는 거리는 간선 셋으로
     * 끝나므로, 그것으로 <em>먼저</em> 줄이고 몇 자리만 정확히 잰다. 근사는 후보를 줄이는 데만
     * 쓰고 채택 판정에는 쓰지 않는다.
     */
    private static int[] nearestPositions(PlanningProblem problem, List<Stop> route, Stop stop) {
        int slots = route.size() + 1;
        int keep = Math.min(TRIED_POSITIONS, slots);
        int[] best = new int[keep];
        long[] bestDelta = new long[keep];
        int filled = 0;

        for (int at = 0; at < slots; at++) {
            GeoPoint before = at == 0 ? problem.depot().point() : route.get(at - 1).point();
            GeoPoint after = at == route.size() ? problem.depot().point() : route.get(at).point();
            long delta = problem.distance().between(before, stop.point()).meters()
                    + problem.distance().between(stop.point(), after).meters()
                    - problem.distance().between(before, after).meters();
            if (filled == keep && delta >= bestDelta[keep - 1]) {
                continue;
            }
            int slot = filled < keep ? filled++ : keep - 1;
            while (slot > 0 && bestDelta[slot - 1] > delta) {
                bestDelta[slot] = bestDelta[slot - 1];
                best[slot] = best[slot - 1];
                slot--;
            }
            bestDelta[slot] = delta;
            best[slot] = at;
        }
        return java.util.Arrays.copyOf(best, filled);
    }

    /** 고른 자리. */
    private record Placement(int route, List<Stop> stops, long cost) {
    }
}
