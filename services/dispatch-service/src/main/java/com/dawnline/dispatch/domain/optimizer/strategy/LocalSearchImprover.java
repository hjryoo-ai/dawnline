package com.dawnline.dispatch.domain.optimizer.strategy;

import com.dawnline.common.GeoPoint;
import com.dawnline.dispatch.domain.optimizer.PlannedStop;
import com.dawnline.dispatch.domain.optimizer.PlanningProblem;
import com.dawnline.dispatch.domain.optimizer.RouteAccumulator;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * 국소 탐색 개선 (DESIGN.md §6.5 5단계) — 2-opt · Or-opt · inter-route relocate/swap.
 *
 * <h2>무엇을 바꾸고 무엇을 안 바꾸는가</h2>
 * 배정된 stop 의 <strong>순서와 소속만</strong> 바꾼다. stop 집합은 그대로다 — 실린 것을 빼지도,
 * 미배정을 싣지도 않는다. 그래서 목적함수(§6.1) 중 미배정 페널티는 상수이고, 이 단계가 줄이는 것은
 * 라우트 비용의 합뿐이다. 라우트가 통째로 비면 그 차의 고정비가 사라지는 것도 여기 포함된다.
 *
 * <h2>후보를 재는 방법 — 다시 넣어 본다</h2>
 * 이동 하나의 값을 근사식으로 재지 않고 <strong>그 순서로 라우트를 다시 만들어</strong> 하드 룰을
 * 통과하는지와 비용이 얼마인지를 본다. {@link GreedyAssigner} 가 한계비용을 실제 배치로 재는 것과
 * 같은 이유다 — 근사식은 룰과 갈라지고, 갈라지는 순간 "왜 이 순서인가" 에 답할 수 없다. 룰이
 * 데이터인 설계에서(§6.3) 근사식은 <em>룰을 코드에 두 번째로 적는 일</em>이다.
 *
 * <h2>그래서 후보 수를 줄인다 — 두 겹</h2>
 * <ol>
 *   <li><strong>이웃 표</strong>({@link Neighborhood}). 새로 생기는 간선이 K-최근접 안인 이동만
 *       만든다. 개선하는 이동은 거의 언제나 짧은 간선을 만들기 때문이다.</li>
 *   <li><strong>거리 선별</strong>. 만들어진 후보 중 <em>이동 거리가 줄지 않는 것</em>은 룰을 돌리기
 *       전에 버린다. 끊는 간선과 잇는 간선만 재면 되므로 후보 하나에 하버사인 4~8번이고, 라우트를
 *       다시 만드는 것보다 두 자릿수 싸다.</li>
 * </ol>
 * 둘 다 근사이고 대가가 있다. 특히 2번은 <strong>거리는 늘지만 지각 페널티가 더 크게 주는
 * 이동</strong>을 보지 못한다. 그 대가는 실측해 {@code docs/benchmarks/phase4-local-search.md}
 * 에 적었다 — 선별을 끄고 같은 문제를 푼 결과와의 차이다.
 *
 * <h2>예산과 결정성</h2>
 * 불변규칙 12 는 같은 입력이 같은 결과를 내라고 하는데, 시간 예산은 그 자체가 벽시계에 달렸다.
 * 두 규칙을 이렇게 화해시킨다 — <strong>패스는 통째로 적용되거나 통째로 버려진다.</strong>
 * 결과는 결정적인 수열 S₀ ⊇ S₁ ⊇ … 의 한 원소이고, 예산은 <em>어디까지 갔는지만</em> 정한다.
 * 느린 기계에서는 앞쪽 원소가 나오지 <em>다른</em> 답이 나오지 않는다. 패스를 시작할지는 직전
 * 패스의 소요 시간으로 판단하므로(첫 패스만 중간에 버려질 수 있다) 버려지는 일도 드물다.
 *
 * <p>종료 조건은 셋이다: 개선 없음(국소 최적) · 개선 폭 &lt; 0.1% · 예산 소진. §6.5 5단계 그대로다.
 */
public final class LocalSearchImprover {

    /** stop 하나당 후보 이웃 수 (K). */
    private static final int NEIGHBORS = 20;

    /** Or-opt·relocate 로 옮기는 묶음의 최대 길이. */
    private static final int MAX_SEGMENT = 3;

    /** 한 패스의 개선 폭이 이 비율 아래면 멈춘다 (§6.5 5단계). */
    private static final double MIN_GAIN_RATIO = 0.001d;

    /** 안전 상한. 0.1% 규칙이 먼저 걸리는 것이 정상이다. */
    private static final int MAX_PASSES = 50;

    private final LongSupplier nanoTime;

    /** 운영용. 경과 시간만 재므로 {@code System.nanoTime()} 이고, 이것은 시계가 아니라 스톱워치다. */
    public LocalSearchImprover() {
        this(System::nanoTime);
    }

    /**
     * @param nanoTime 경과 시간 원천. 테스트가 예산 소진을 결정적으로 만들 때 바꾼다
     */
    public LocalSearchImprover(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * 개선한다.
     *
     * @param problem      계획 입력
     * @param seeded       차량 순서대로의 라우트들 (빈 것 포함). 이 목록은 바뀌지 않는다
     * @param elapsedNanos 앞 단계들이 이미 쓴 시간. 개선 예산은
     *                     {@code (budget.total() − 이 값) × budgetFactor} 다 (§6.7 사다리)
     * @return 개선된 라우트들. 입력과 같은 순서·같은 길이다
     */
    public Outcome improve(PlanningProblem problem, List<RouteAccumulator> seeded,
            long elapsedNanos) {

        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(seeded, "seeded");
        long remaining = problem.improvementNanos(elapsedNanos);
        return new Search(problem, seeded, nanoTime.getAsLong() + remaining).run();
    }

    /**
     * 개선 결과.
     *
     * @param routes           개선된 라우트들 (입력과 같은 순서)
     * @param passes           끝까지 돈 패스 수
     * @param gainKrw          줄인 비용
     * @param budgetExhausted  예산이 끝나 멈췄는가. 국소 최적에 닿아 멈춘 것과 구별한다
     */
    public record Outcome(List<RouteAccumulator> routes, int passes, long gainKrw,
            boolean budgetExhausted) {

        public Outcome {
            routes = List.copyOf(Objects.requireNonNull(routes, "routes"));
        }
    }

    /** 탐색 한 번의 상태. 개선기 자체는 상태를 들지 않는다 (불변규칙 12). */
    private final class Search {

        private final PlanningProblem problem;
        private final long deadlineNanos;
        private final long perRouteNanos;
        private final List<VehicleSpec> vehicles;
        private final List<List<Stop>> routes;
        private final long[] costs;
        private final Stop[] all;
        private final Map<Stop, Integer> index = new IdentityHashMap<>();
        private final int[] routeOf;
        private final Neighborhood near;

        Search(PlanningProblem problem, List<RouteAccumulator> seeded, long deadlineNanos) {
            this.problem = problem;
            this.deadlineNanos = deadlineNanos;
            this.perRouteNanos = problem.budget().perRoute().toNanos();
            this.vehicles = seeded.stream().map(route -> route.state().vehicle()).toList();
            this.routes = new ArrayList<>();

            List<Stop> flat = new ArrayList<>();
            for (RouteAccumulator route : seeded) {
                List<Stop> stops = new ArrayList<>();
                for (PlannedStop planned : route.state().stops()) {
                    stops.add(planned.stop());
                }
                routes.add(stops);
                flat.addAll(stops);
            }
            this.all = flat.toArray(Stop[]::new);
            for (int i = 0; i < all.length; i++) {
                if (index.put(all[i], i) != null) {
                    // 같은 stop 이 두 번 실렸다는 뜻이고, 그건 개선 이전의 버그다. 조용히
                    // 지나가면 이 단계가 그 stop 하나를 잃는다.
                    throw new IllegalStateException(
                            "같은 stop 이 두 라우트에 실려 있습니다: " + all[i].orderIds());
                }
            }
            this.routeOf = new int[all.length];
            int at = 0;
            for (int r = 0; r < routes.size(); r++) {
                for (int i = 0; i < routes.get(r).size(); i++) {
                    routeOf[at++] = r;
                }
            }
            this.costs = new long[routes.size()];
            for (int r = 0; r < routes.size(); r++) {
                costs[r] = costOf(r, routes.get(r));
            }
            this.near = Neighborhood.of(List.of(all), NEIGHBORS);
        }

        Outcome run() {
            long before = total();
            if (all.length < 3 || before <= 0L) {
                return new Outcome(rebuild(), 0, 0L, false);
            }

            Snapshot best = snapshot();
            long bestCost = before;
            int passes = 0;
            boolean exhausted = false;
            long lastPassNanos = 0L;

            while (passes < MAX_PASSES) {
                long now = nanoTime.getAsLong();
                // 직전 패스만큼 더 걸린다고 보고, 넘칠 것 같으면 시작하지 않는다.
                if (now >= deadlineNanos || now + lastPassNanos > deadlineNanos) {
                    exhausted = true;
                    break;
                }
                boolean completed = pass();
                lastPassNanos = nanoTime.getAsLong() - now;
                if (!completed) {
                    exhausted = true;
                    break;                      // 반쯤 돈 패스는 버린다 — 아래에서 best 로 되돌린다
                }
                passes++;
                long cost = total();
                long gain = bestCost - cost;
                best = snapshot();
                bestCost = cost;
                if (gain <= 0L || (double) gain / before < MIN_GAIN_RATIO) {
                    break;
                }
            }

            restore(best);
            return new Outcome(rebuild(), passes, before - bestCost, exhausted);
        }

        /** 한 패스. 예산이 끝나 중간에 멈췄으면 {@code false}. */
        private boolean pass() {
            for (int r = 0; r < routes.size(); r++) {
                if (!intra(r)) {
                    return false;
                }
            }
            return inter();
        }

        // ---------------------------------------------------------------- 라우트 안

        /** 라우트 하나의 2-opt·Or-opt. 라우트별 예산({@code budget.perRoute})을 여기서 쓴다. */
        private boolean intra(int r) {
            if (routes.get(r).size() < 3) {
                return true;
            }
            long routeDeadline = Math.min(deadlineNanos, nanoTime.getAsLong() + perRouteNanos);
            for (int p = 0; p < routes.get(r).size(); p++) {
                if (nanoTime.getAsLong() >= routeDeadline) {
                    // 라우트 예산이 끝난 것뿐이면 다음 라우트로 넘어간다 — 패스는 계속이다.
                    return nanoTime.getAsLong() < deadlineNanos;
                }
                if (!twoOptAt(r, p)) {
                    orOptAt(r, p);
                }
            }
            return true;
        }

        /**
         * {@code route[p]} 와 이웃 하나를 잇는 2-opt. 끊는 간선은 {@code (p, p+1)} 과
         * {@code (q, q+1)}, 잇는 간선은 {@code (p, q)} 와 {@code (p+1, q+1)} 이고, 사이 구간을
         * 뒤집는다. 하버사인이 대칭이라 뒤집힌 구간의 내부 길이는 변하지 않는다.
         */
        private boolean twoOptAt(int r, int p) {
            List<Stop> route = routes.get(r);
            for (int t : near.of(index.get(route.get(p)))) {
                if (routeOf[t] != r) {
                    continue;
                }
                int q = positionOf(route, all[t]);
                if (q <= p + 1) {
                    continue;
                }
                long delta = meters(route, p, p + 1) + meters(route, q, q + 1)
                        - meters(route, p, q) - meters(route, p + 1, q + 1);
                if (delta <= 0L) {
                    continue;                   // 거리가 줄지 않는다 — 룰을 돌리지 않는다
                }
                List<Stop> candidate = new ArrayList<>(route);
                Collections.reverse(candidate.subList(p + 1, q + 1));
                long after = costOf(r, candidate);
                if (after < costs[r]) {
                    commit(r, candidate, after);
                    return true;
                }
            }
            return false;
        }

        /** {@code route[p]} 에서 시작하는 1~3개 묶음을 같은 라우트의 다른 자리로 옮긴다. */
        private boolean orOptAt(int r, int p) {
            List<Stop> route = routes.get(r);
            for (int len = 1; len <= MAX_SEGMENT && p + len <= route.size(); len++) {
                for (int t : near.of(index.get(route.get(p)))) {
                    if (routeOf[t] != r) {
                        continue;
                    }
                    int q = positionOf(route, all[t]);
                    if (q >= p - 1 && q < p + len) {
                        continue;               // 자기 묶음 안이거나 제자리다
                    }
                    for (boolean reversed : BOTH) {
                        // 거리 선별을 <em>먼저</em> 한다 — 후보 목록을 만드는 것도 비용이다.
                        if (detachMeters(route, p, len)
                                + attachMeters(route, q, p, len, reversed) >= 0L) {
                            continue;
                        }
                        List<Stop> candidate = relocated(route, p, len, all[t], reversed);
                        if (candidate == null) {
                            continue;
                        }
                        long after = costOf(r, candidate);
                        if (after < costs[r]) {
                            commit(r, candidate, after);
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        // ---------------------------------------------------------------- 라우트 사이

        /** 라우트 사이의 relocate 와 swap. */
        private boolean inter() {
            for (int r1 = 0; r1 < routes.size(); r1++) {
                int p = 0;
                while (p < routes.get(r1).size()) {
                    if (nanoTime.getAsLong() >= deadlineNanos) {
                        return false;
                    }
                    if (!interAt(r1, p)) {
                        p++;                    // 길이가 그대로면 다음 자리로
                    }
                }
            }
            return true;
        }

/**
         * 이 자리의 stop 을 다른 라우트로 옮기거나 맞바꿔 본다.
         *
         * @return 원래 라우트가 <strong>짧아졌으면</strong> {@code true} — 그러면 같은 자리에
         *         다른 stop 이 밀려 들어오므로 호출부가 인덱스를 올리지 않는다
         */
        private boolean interAt(int r1, int p) {
            List<Stop> from = routes.get(r1);
            Stop moving = from.get(p);
            for (int t : near.of(index.get(moving))) {
                int r2 = routeOf[t];
                if (r2 == r1) {
                    continue;
                }
                List<Stop> to = routes.get(r2);
                int q = positionOf(to, all[t]);
                long base = costs[r1] + costs[r2];

                // relocate — moving 에서 시작하는 1~3개 묶음을 이웃 t 뒤에 붙인다
                for (int len = 1; len <= MAX_SEGMENT && p + len <= from.size(); len++) {
                    // 원래 라우트가 <strong>통째로 비는</strong> 이동은 거리 선별을 건너뛴다.
                    // 그 이동의 이득은 거리가 아니라 차량 고정비이고(§6.4 — 빈 라우트는 고정비를
                    // 물지 않는다), 거리만 보는 선별은 그 이득을 볼 수 없다. large 에서 고정비는
                    // 총비용의 19%다 — 선별이 가장 값진 이동을 가리는 자리다.
                    boolean emptiesSource = from.size() == len;
                    for (boolean reversed : BOTH) {
                        Stop head = from.get(reversed ? p + len - 1 : p);
                        Stop tail = from.get(reversed ? p : p + len - 1);
                        if (!emptiesSource
                                && detachMeters(from, p, len) + insertMeters(to, q, head, tail) >= 0L) {
                            continue;
                        }
                        List<Stop> source = new ArrayList<>(from);
                        List<Stop> segment = new ArrayList<>(source.subList(p, p + len));
                        source.subList(p, p + len).clear();
                        if (reversed) {
                            Collections.reverse(segment);
                        }
                        List<Stop> target = new ArrayList<>(to);
                        target.addAll(q + 1, segment);
                        long left = costOf(r1, source);
                        long right = costOf(r2, target);
                        if (combined(left, right) < base) {
                            commit(r1, source, left, r2, target, right);
                            return true;
                        }
                    }
                }

                // swap — moving 과 t 를 맞바꾼다
                Stop other = all[t];
                if (swapMeters(from, p, other) + swapMeters(to, q, moving) < 0L) {
                    List<Stop> source = new ArrayList<>(from);
                    List<Stop> target = new ArrayList<>(to);
                    source.set(p, other);
                    target.set(q, moving);
                    long left = costOf(r1, source);
                    long right = costOf(r2, target);
                    if (combined(left, right) < base) {
                        commit(r1, source, left, r2, target, right);
                        return false;           // 맞바꾸기는 길이를 바꾸지 않는다
                    }
                }
            }
            return false;
        }

        // ---------------------------------------------------------------- 거리 선별

        /**
         * 두 자리 사이의 거리(m). {@code -1} 과 {@code size} 는 캠프다 — 출발·복귀 구간도
         * 라우트가 만든 거리이므로 경계를 특별 취급하지 않는다.
         */
        private long meters(List<Stop> route, int i, int j) {
            return problem.distance().between(pointAt(route, i), pointAt(route, j)).meters();
        }

        private GeoPoint pointAt(List<Stop> route, int i) {
            return i < 0 || i >= route.size() ? problem.depot().point() : route.get(i).point();
        }

        /** 묶음 {@code [start, start+len)} 을 떼면 줄어드는 거리(보통 음수). */
        private long detachMeters(List<Stop> route, int start, int len) {
            int end = start + len - 1;
            return meters(route, start - 1, end + 1)
                    - meters(route, start - 1, start) - meters(route, end, end + 1);
        }

        /** 묶음을 같은 라우트의 {@code q} 뒤에 붙일 때 늘어나는 거리. */
        private long attachMeters(List<Stop> route, int q, int start, int len, boolean reversed) {
            Stop head = route.get(reversed ? start + len - 1 : start);
            Stop tail = route.get(reversed ? start : start + len - 1);
            return insertMeters(route, q, head, tail);
        }

        /** {@code route[q]} 와 그 다음 사이에 {@code head…tail} 을 끼울 때 늘어나는 거리. */
        private long insertMeters(List<Stop> route, int q, Stop head, Stop tail) {
            GeoPoint before = pointAt(route, q);
            GeoPoint after = pointAt(route, q + 1);
            return problem.distance().between(before, head.point()).meters()
                    + problem.distance().between(tail.point(), after).meters()
                    - problem.distance().between(before, after).meters();
        }

        /** {@code route[p]} 를 {@code other} 로 갈아 끼울 때의 거리 변화. */
        private long swapMeters(List<Stop> route, int p, Stop other) {
            GeoPoint before = pointAt(route, p - 1);
            GeoPoint after = pointAt(route, p + 1);
            GeoPoint here = route.get(p).point();
            return problem.distance().between(before, other.point()).meters()
                    + problem.distance().between(other.point(), after).meters()
                    - problem.distance().between(before, here).meters()
                    - problem.distance().between(here, after).meters();
        }

        // ---------------------------------------------------------------- 평가와 적용

        /** 이 순서로 라우트를 다시 만들었을 때의 비용. 판정은 재삽입과 공유한다. */
        private long costOf(int r, List<Stop> stops) {
            return RouteRebuild.cost(problem, vehicles.get(r), stops);
        }

        private static long combined(long left, long right) {
            return RouteRebuild.combined(left, right);
        }

        private void commit(int r, List<Stop> stops, long cost) {
            routes.set(r, stops);
            costs[r] = cost;
        }

        private void commit(int r1, List<Stop> first, long firstCost, int r2, List<Stop> second,
                long secondCost) {

            routes.set(r1, first);
            routes.set(r2, second);
            costs[r1] = firstCost;
            costs[r2] = secondCost;
            for (Stop stop : first) {
                routeOf[index.get(stop)] = r1;
            }
            for (Stop stop : second) {
                routeOf[index.get(stop)] = r2;
            }
        }

        private long total() {
            long sum = 0L;
            for (long cost : costs) {
                sum += cost;
            }
            return sum;
        }

        private List<RouteAccumulator> rebuild() {
            List<RouteAccumulator> built = new ArrayList<>(routes.size());
            for (int r = 0; r < routes.size(); r++) {
                RouteAccumulator route =
                        RouteRebuild.accumulate(problem, vehicles.get(r), routes.get(r));
                if (route == null) {
                    // 받아들인 이동은 전부 실행 가능했다. 여기서 걸리면 개선 코드의 버그다.
                    throw new IllegalStateException(
                            "개선된 라우트가 하드 룰을 어깁니다: 차량 " + vehicles.get(r).id());
                }
                built.add(route);
            }
            return built;
        }

        private Snapshot snapshot() {
            List<List<Stop>> copy = new ArrayList<>(routes.size());
            routes.forEach(stops -> copy.add(List.copyOf(stops)));
            return new Snapshot(copy, costs.clone(), routeOf.clone());
        }

        private void restore(Snapshot snapshot) {
            routes.clear();
            snapshot.routes().forEach(stops -> routes.add(new ArrayList<>(stops)));
            System.arraycopy(snapshot.costs(), 0, costs, 0, costs.length);
            System.arraycopy(snapshot.routeOf(), 0, routeOf, 0, routeOf.length);
        }

        private @Nullable List<Stop> relocated(List<Stop> route, int start, int len, Stop anchor,
                boolean reversed) {

            List<Stop> next = new ArrayList<>(route);
            List<Stop> segment = new ArrayList<>(next.subList(start, start + len));
            next.subList(start, start + len).clear();
            if (reversed) {
                Collections.reverse(segment);
            }
            int at = positionOf(next, anchor);
            if (at < 0) {
                return null;
            }
            next.addAll(at + 1, segment);
            return next;
        }
    }

    /** 마지막으로 <em>끝까지 돈</em> 패스의 결과. 예산이 중간에 끊기면 여기로 되돌린다. */
    private record Snapshot(List<List<Stop>> routes, long[] costs, int[] routeOf) {
    }

    private static final boolean[] BOTH = {false, true};

    /**
     * 목록에서 이 stop 의 자리. <strong>동일성으로</strong> 찾는다 — {@link Stop} 은 레코드라
     * 값이 같은 서로 다른 객체가 있을 수 있고, 그때 {@code indexOf} 는 엉뚱한 자리를 준다.
     */
    private static int positionOf(List<Stop> route, Stop stop) {
        for (int i = 0; i < route.size(); i++) {
            if (route.get(i) == stop) {
                return i;
            }
        }
        return -1;
    }
}
