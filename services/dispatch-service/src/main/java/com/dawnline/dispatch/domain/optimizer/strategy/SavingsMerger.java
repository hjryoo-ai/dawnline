package com.dawnline.dispatch.domain.optimizer.strategy;

import com.dawnline.common.GeoPoint;
import com.dawnline.dispatch.domain.optimizer.ConstraintClass;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.PlanningDeadline;
import com.dawnline.dispatch.domain.optimizer.PlanningProblem;
import com.dawnline.dispatch.domain.optimizer.RouteAccumulator;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/**
 * Clarke-Wright savings 로 라우트를 <strong>구성</strong>한다 (DESIGN.md §6.6 {@code savings-cw+ls}
 * · [ADR-042]).
 *
 * <h2>무엇이 다른가 — 클러스터가 없다</h2>
 * {@link SweepClusterer} 는 <em>먼저 자르고</em> 그 묶음을 차에 붙인다. savings 는 반대다 —
 * stop 하나짜리 라우트 n 개에서 시작해 <strong>합쳐서 아끼는 거리</strong>가 큰 쌍부터 이어 붙인다.
 *
 * <pre>
 * s(i, j) = d(캠프, i) + d(캠프, j) − d(i, j)
 * </pre>
 *
 * <p>「i 와 j 를 따로 다녀오는 대신 한 라우트에 이어 붙이면 아끼는 거리」다. 정렬은 내림차순이고,
 * 동률은 stop 인덱스 순이다 — 같은 seed 가 같은 결과를 내야 한다(불변규칙 12).
 *
 * <h2>쌍은 K-최근접 안에서만 본다</h2>
 * 완전한 savings 목록은 {@code n²/2} 쌍이다 — {@code peak}(통합 후 8,411 stop)에서 <strong>3,500만
 * 쌍</strong>이고, 만들어 정렬하는 것만으로 예산이 끝난다. 그래서 {@link Neighborhood} 의 K-최근접
 * 표 안에 있는 쌍만 만든다. 근거는 기하다 — {@code s(i, j)} 가 큰 쌍은 «서로 가깝고 캠프에서 먼»
 * 쌍이고, 그런 쌍은 거의 언제나 서로의 최근접 안에 있다.
 *
 * <p>이 근사는 <strong>개선을 덜 찾는 방향으로만 틀린다</strong> — 표 밖의 병합을 못 볼 뿐,
 * 실행 불가능한 병합을 하지는 않는다. 개선 단계가 쓰는 근사([ADR-032])와 같은 표, 같은 K 다
 * ({@link Neighborhood#DEFAULT_K}): 두 단계가 다른 표를 쓰면 §6.9 의 비교가 「구성 방식의 차이」가
 * 아니라 「표 크기의 차이」를 재게 된다.
 *
 * <h2>그리고 끝점은 전부 본다 (2단계, [ADR-044])</h2>
 * K-최근접은 <strong>stop 이 8,411개일 때</strong> 필요한 근사다. 1단계가 끝나면 라우트는
 * {@code peak} 에서 216개뿐이고, <strong>이을 수 있는 자리는 그 라우트들의 끝점</strong>
 * (꼬리 → 머리)밖에 없다 — 216 × 215 = 46,090 쌍이다. 그 크기에서는 근사할 이유가 없다.
 *
 * <p>근사를 계속하면 무엇을 잃는지도 쟀다({@code docs/benchmarks/phase4-endpoint-merges.md}).
 * 1단계가 끝난 {@code peak} 의 라우트 216개 중 <strong>67개가 stop 하나짜리</strong>다 — 끝점의
 * 최근접 20개가 <em>이미 같은 라우트에 들어간</em> stop 들로 채워져 이을 후보가 없어진 것이고,
 * 그래서 1단계를 한 번 더 돌려도 한 건도 더 잇지 못한다(같은 K 안에서 이미 고정점). 끝점만 전부
 * 보면 <strong>216 → 90</strong>, 차량 88대에 거의 맞는다.
 *
 * <p>쌍의 예산은 <strong>1단계보다 많이 만들지 않는다</strong>: {@code R(R−1) > n·K} 면 2단계를
 * 돌지 않는다. 그 부등식이 참인 구간은 라우트가 stop 수에 가까운 구간이고, 거기서는 끝점이 곧
 * 전체 stop 이라 <em>K 표가 이미 본 쌍</em>이다 — 값을 하지 않는 자리에서 {@code O(n²)} 를 쓰지
 * 않겠다는 뜻이다.
 *
 * <h2>병합 판정은 <strong>조합을 안다</strong></h2>
 * savings 는 stop 을 이어 붙이며 라우트의 <strong>제약 조합을 키운다</strong>. 냉장 ∧ 위험물 stop
 * 하나가 이웃 100개와 병합되면 그 라우트 전체가 조합 차량을 요구하고, 붙이는 시점의 좌석 예약
 * ([ADR-039])은 <em>이미 만들어진 라우트</em>를 고칠 수 없다. 그러면 savings 가 지는 이유가
 * 「구성 방식」이 아니라 「희소 좌석」이 되어 비교표가 엉뚱한 것을 잰다 — [ADR-038] 이 배운
 * 「잰 항이 틀렸다」와 같은 형태다.
 *
 * <p>그래서 판정이 셋이다.
 * <ol>
 *   <li><strong>합집합 조합의 대표 차량</strong>. 병합 후 라우트의 조합은 두 조합의 합집합이고,
 *       실행 가능성은 <em>그 조합을 덮는 차량 중 가장 큰 것</em>으로 잰다. 덮는 차가 함대에
 *       없으면 병합하지 않는다.</li>
 *   <li><strong>집계 좌석 불변식</strong>([ADR-039] 결정 2 를 구성 단계로 옮긴 것). 조합 c 이상을
 *       요구하는 라우트들의 stop 합은 <em>c 를 덮는 차량들의 슬롯 합</em>을 넘을 수 없다.</li>
 *   <li><strong>하드 룰 전부</strong>. 대표 차량으로 그 순서를 실제로 쌓아 본다 — 근사식을 쓰면
 *       룰을 코드에 두 번째로 적는 일이다(§6.3, {@link RouteRebuild} 와 같은 이유).</li>
 * </ol>
 *
 * <h2>라우트를 뒤집지 않는다</h2>
 * 대칭 거리의 고전 CW 는 라우트를 뒤집어서도 잇는다. 여기서는 잇지 않는다 — 약속창과 근무창이
 * 있어서 <strong>뒤집으면 도착 시각이 전부 달라지고</strong>, 뒤집기는 순서를 바꾸는 이동이므로
 * §6.5 <em>5단계</em>(2-opt)의 일이다. 구성 단계가 그것까지 하면 비교표가 다시 두 가지를 섞는다.
 */
final class SavingsMerger {

    private static final List<ConstraintClass> CLASSES = ConstraintClass.all();

    private static final Map<ConstraintClass, Integer> INDEX = IntStream.range(0, CLASSES.size())
            .boxed().collect(java.util.stream.Collectors.toUnmodifiableMap(CLASSES::get, i -> i));

    /** 집계 불변식이 꺼져 있을 때의 증분 — 어느 축도 움직이지 않는다. */
    private static final int[] NO_DELTA = new int[CLASSES.size()];

    private final PlanningProblem problem;
    private final List<Stop> stops;
    private final PlanningDeadline deadline;

    /** 조합별 대표 차량 — 그 조합을 덮는 차량 중 <strong>가장 큰</strong> 것. 없으면 {@code null}. */
    private final @Nullable VehicleSpec[] representative;

    /** 조합 c 를 덮는 차량들의 stop 슬롯 합. {@link #slotsActive} 가 거짓이면 뜻이 없다. */
    private final long[] classSlots;

    /** 조합 c 이상을 요구하는 <em>지금의</em> 라우트들의 stop 합. */
    private final int[] classLoad;

    /** 룰이 stop 상한을 말하는가. 말하지 않으면 슬롯을 셀 수 없고, 집계 불변식도 없다. */
    private final boolean slotsActive;

    private final int stopCap;

    /** 지금의 라우트 수 — 병합 하나가 하나를 줄인다. */
    private int routeCount;

    /** 캠프 ↔ stop 거리. 1단계와 2단계가 같은 값을 쓴다. */
    private int @Nullable [] depotCache;

    // 라우트는 「연결 리스트 + 유니온-파인드」다. 병합이 O(1) 이고, 라우트의 stop 목록은
    // 필요할 때만 head 에서 걸어 만든다.
    private final int[] parent;
    private final int[] head;
    private final int[] tail;
    private final int[] next;
    private final int[] sizes;
    private final Parcel[] loads;
    private final ConstraintClass[] klass;

    /**
     * 루트별로 <strong>지금까지 쌓은 라우트</strong>. 대표 차량이 그대로면 뒤쪽만 이어 붙이면
     * 되므로 병합 하나가 {@code O(뒤쪽 길이)} 다 — 매번 처음부터 쌓으면 {@code O(전체 길이)} 다.
     */
    private final @Nullable RouteAccumulator[] built;

    private SavingsMerger(PlanningProblem problem, List<Stop> stops, PlanningDeadline deadline) {
        this.problem = problem;
        this.stops = stops;
        this.deadline = deadline;

        int n = stops.size();
        this.parent = IntStream.range(0, n).toArray();
        this.head = IntStream.range(0, n).toArray();
        this.tail = IntStream.range(0, n).toArray();
        this.next = new int[n];
        java.util.Arrays.fill(this.next, -1);
        this.sizes = new int[n];
        java.util.Arrays.fill(this.sizes, 1);
        this.loads = new Parcel[n];
        this.klass = new ConstraintClass[n];
        this.built = new RouteAccumulator[n];
        for (int i = 0; i < n; i++) {
            loads[i] = stops.get(i).parcel();
            klass[i] = ConstraintClass.of(stops.get(i));
        }

        this.representative = representatives(problem.vehicles());
        OptionalInt cap = problem.rules().routeStopCap();
        this.slotsActive = cap.isPresent() && cap.getAsInt() > 0;
        this.stopCap = slotsActive ? cap.getAsInt() : 0;
        this.routeCount = n;
        this.classSlots = new long[CLASSES.size()];
        this.classLoad = new int[CLASSES.size()];
        for (int c = 0; c < CLASSES.size(); c++) {
            ConstraintClass klazz = CLASSES.get(c);
            classSlots[c] = problem.vehicles().stream().filter(klazz::carriedBy).count()
                    * stopCap;
            classLoad[c] = (int) stops.stream().filter(stop -> ConstraintClass.of(stop).covers(klazz))
                    .count();
        }
    }

    /**
     * 라우트를 만든다 — <strong>차량은 아직 붙이지 않는다</strong>.
     *
     * @param problem  계획 입력
     * @param stops    통합 후 stop 들
     * @param deadline 계획 전체의 마감 ([ADR-036]). 지나면 지금까지 합친 결과로 끝낸다
     * @return stop 목록들. 각각이 「한 차가 갈 만한 것」이지만 어느 차인지는 다음 단계가 정한다
     */
    static List<List<Stop>> merge(PlanningProblem problem, List<Stop> stops,
            PlanningDeadline deadline) {

        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(stops, "stops");
        Objects.requireNonNull(deadline, "deadline");
        if (stops.isEmpty()) {
            return List.of();
        }
        return new SavingsMerger(problem, stops, deadline).run();
    }

    private List<List<Stop>> run() {
        for (Saving saving : savings()) {
            if (deadline.expired()) {
                break;      // 지금까지 합친 것이 곧 답이다 — 되돌리지 않는다 ([ADR-036])
            }
            tryMerge(saving.left(), saving.right());
        }
        endpointPass();
        List<List<Stop>> routes = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            if (find(i) == i) {
                routes.add(stopsOf(i));
            }
        }
        return List.copyOf(routes);
    }

    /**
     * 2단계 — <strong>끝점 쌍을 전부 본다</strong> ([ADR-044]).
     *
     * <p>1단계가 끝나면 이을 수 있는 자리는 라우트의 끝점뿐이다(꼬리 → 머리, 라우트를 뒤집지
     * 않으므로). 라우트가 {@code R} 개면 쌍은 {@code R(R−1)} 개이고, {@code R} 이 stop 수보다
     * 훨씬 작은 구간에서는 그것이 1단계가 만든 쌍보다도 적다. 그 구간에서는 <strong>근사할
     * 이유가 없다</strong>.
     *
     * <p>고정점까지 돈다 — 한 번 이으면 새 끝점이 생기기 때문이다. 실제로는 두 번째 패스가 한
     * 건도 더 잇지 못하지만({@code large}·{@code peak} 둘 다), 그것은 <em>측정 결과</em>이지
     * 성질이 아니다.
     *
     * <p>상한에 찬 라우트는 후보에서 뺀다. 어느 쪽으로 이어도 stop 상한을 넘으므로 쌍을 만들어
     * 볼 필요가 없다 — {@code peak} 에서 그런 라우트가 12개다.
     */
    private void endpointPass() {
        long pairBudget = (long) stops.size() * Neighborhood.DEFAULT_K;
        while (!deadline.expired()) {
            int[] roots = mergeableRoots();
            if ((long) roots.length * (roots.length - 1) > pairBudget) {
                return;     // 1단계보다 많은 쌍을 만들지 않는다 — 클래스 주석의 부등식
            }
            int before = routeCount;
            for (Saving saving : endpointSavings(roots)) {
                if (deadline.expired()) {
                    break;
                }
                tryMerge(saving.left(), saving.right());
            }
            if (routeCount == before) {
                return;     // 고정점
            }
        }
    }

    /** 아직 이을 수 있는 라우트들의 루트 — 인덱스 순이라 결과가 결정적이다(불변규칙 12). */
    private int[] mergeableRoots() {
        return IntStream.range(0, stops.size())
                .filter(i -> find(i) == i)
                .filter(i -> !slotsActive || sizes[i] < stopCap)
                .toArray();
    }

    /** 끝점 쌍의 savings — 1단계와 같은 식, 같은 정렬, 같은 동률 규칙. */
    private List<Saving> endpointSavings(int[] roots) {
        int[] toDepot = depotDistances();
        List<Saving> savings = new ArrayList<>();
        for (int first : roots) {
            int from = tail[first];
            for (int second : roots) {
                if (first == second) {
                    continue;
                }
                int to = head[second];
                int between = problem.distance()
                        .between(stops.get(from).point(), stops.get(to).point()).meters();
                long value = (long) toDepot[from] + toDepot[to] - between;
                if (value > 0L) {
                    savings.add(new Saving(from, to, value));
                }
            }
        }
        savings.sort(Comparator.comparingLong(Saving::meters).reversed()
                .thenComparingInt(Saving::left).thenComparingInt(Saving::right));
        return savings;
    }

    /** 캠프 ↔ stop 거리 (한 번만 잰다). */
    private int[] depotDistances() {
        int[] cached = depotCache;
        if (cached == null) {
            GeoPoint depot = problem.depot().point();
            cached = new int[stops.size()];
            for (int i = 0; i < stops.size(); i++) {
                cached[i] = problem.distance().between(depot, stops.get(i).point()).meters();
            }
            depotCache = cached;
        }
        return cached;
    }

    /**
     * savings 목록 — 내림차순, 동률은 stop 인덱스 순.
     *
     * <p>{@code s ≤ 0} 인 쌍은 만들지 않는다. 이어 붙여서 아끼는 것이 없다는 뜻이라 병합할 이유가
     * 없고, 목록이 짧아지는 만큼 정렬이 싸진다.
     */
    private List<Saving> savings() {
        Neighborhood near = Neighborhood.of(stops, Neighborhood.DEFAULT_K);
        int[] toDepot = depotDistances();

        // 표는 대칭이 아니다 — j 가 i 의 최근접이어도 그 반대가 아닐 수 있다. 그래서 쌍을
        // (작은 인덱스, 큰 인덱스) 로 정규화해 한 번만 담는다.
        Set<Long> seen = new HashSet<>();
        List<Saving> savings = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            for (int j : near.of(i)) {
                int left = Math.min(i, j);
                int right = Math.max(i, j);
                if (!seen.add(((long) left << 32) | right)) {
                    continue;
                }
                int between = problem.distance()
                        .between(stops.get(left).point(), stops.get(right).point()).meters();
                long value = (long) toDepot[left] + toDepot[right] - between;
                if (value > 0L) {
                    savings.add(new Saving(left, right, value));
                }
            }
        }
        savings.sort(Comparator.comparingLong(Saving::meters).reversed()
                .thenComparingInt(Saving::left).thenComparingInt(Saving::right));
        return savings;
    }

    /**
     * 이 쌍으로 두 라우트를 잇는다 — <strong>끝과 시작이 맞을 때만</strong>.
     *
     * <p>{@code i} 가 어느 라우트의 끝이고 {@code j} 가 다른 라우트의 시작이면 그 순서로 잇는다.
     * 둘 다 성립하면(양쪽이 stop 하나짜리) 앞의 것을 쓴다 — 동률을 코드 순서로 깨는 것이고,
     * 그래서 결정적이다.
     */
    private void tryMerge(int i, int j) {
        int left = find(i);
        int right = find(j);
        if (left == right) {
            return;         // 이미 같은 라우트다 — 잇는다는 말이 성립하지 않는다
        }
        if (tail[left] == i && head[right] == j) {
            merge(left, right);
            return;
        }
        if (tail[right] == j && head[left] == i) {
            merge(right, left);
        }
    }

    /** {@code first} 뒤에 {@code second} 를 잇는다. 셋 중 하나라도 걸리면 잇지 않는다. */
    private void merge(int first, int second) {
        ConstraintClass merged = new ConstraintClass(
                klass[first].cold() || klass[second].cold(),
                klass[first].hazmat() || klass[second].hazmat());
        VehicleSpec vehicle = representative[INDEX.get(merged)];
        if (vehicle == null) {
            return;         // 이 조합을 실을 차가 함대에 없다
        }
        Parcel load = loads[first].plus(loads[second]);
        if (!vehicle.capacity().admits(load)) {
            return;
        }
        int size = sizes[first] + sizes[second];
        if (slotsActive && size > stopCap) {
            return;
        }
        int[] delta = slotDelta(first, second, merged);
        if (delta == null) {
            return;         // 집계 좌석 불변식 — 이 병합은 희소 조합의 자리를 없앤다
        }
        RouteAccumulator route = accumulate(first, second, vehicle, merged);
        if (route == null) {
            return;         // 하드 룰이 거절했다
        }

        next[tail[first]] = head[second];
        tail[first] = tail[second];
        parent[second] = first;
        routeCount--;
        sizes[first] = size;
        loads[first] = load;
        klass[first] = merged;
        built[first] = route;
        for (int c = 0; c < delta.length; c++) {
            classLoad[c] += delta[c];
        }
    }

    /**
     * [ADR-039] 의 집계 불변식을 <strong>구성 단계에서</strong> 지킨다.
     *
     * <p>조합 {@code c} 이상을 요구하는 라우트들은 «c 를 덮는 차량» 이라는 같은 풀을 두고 다툰다.
     * 그 풀의 크기는 슬롯 수로 정해져 있으므로, 병합이 그 풀의 수요를 <em>늘리면서</em> 공급을
     * 넘기면 잇지 않는다. 일반 stop 이 냉장 ∧ 위험물 라우트에 붙는 순간 그 stop 도 조합 차량을
     * 요구하게 되는 것이 정확히 이 증가다.
     *
     * <p>이미 공급을 넘긴 축에서는 <strong>늘리지 않는 병합만</strong> 허용한다 — 그 초과는 이
     * 병합이 만든 것이 아니라 수요와 함대가 만든 것이고, 여기서 막으면 병합이 통째로 멈춘다.
     *
     * @return 축별 증분. 불변식을 어기면 {@code null}
     */
    private int @Nullable [] slotDelta(int first, int second, ConstraintClass merged) {
        if (!slotsActive) {
            // 자리를 세지 않는 축에서는 좌석이 희소할 수 없다 ([ADR-039] 결정 2 와 같은 이유).
            return NO_DELTA;
        }
        int[] delta = new int[CLASSES.size()];
        for (int c = 0; c < CLASSES.size(); c++) {
            ConstraintClass klazz = CLASSES.get(c);
            int before = (klass[first].covers(klazz) ? sizes[first] : 0)
                    + (klass[second].covers(klazz) ? sizes[second] : 0);
            int after = merged.covers(klazz) ? sizes[first] + sizes[second] : 0;
            delta[c] = after - before;
            if (delta[c] > 0 && (long) classLoad[c] + delta[c] > classSlots[c]) {
                return null;
            }
        }
        return delta;
    }

    /**
     * 이어 붙인 순서를 <strong>대표 차량으로 실제로 쌓아 본다</strong>.
     *
     * <p>조합이 그대로면 앞쪽은 이미 쌓아 둔 것을 갈라 쓰고 뒤쪽만 얹는다. 조합이 커지면 대표
     * 차량이 바뀌므로 처음부터 쌓는다 — 다른 차의 근무창·용량으로 잰 결과를 물려받을 수는 없다.
     *
     * @return 하드 룰을 어기면 {@code null}
     */
    private @Nullable RouteAccumulator accumulate(int first, int second, VehicleSpec vehicle,
            ConstraintClass merged) {

        RouteAccumulator route;
        List<Stop> rest;
        if (merged.equals(klass[first]) && built[first] != null) {
            route = built[first].branch();
            rest = stopsOf(second);
        } else {
            route = new RouteAccumulator(problem.rules(), vehicle, problem.depot(),
                    problem.distance(), problem.startedAt());
            rest = new ArrayList<>(stopsOf(first));
            rest.addAll(stopsOf(second));
        }
        for (Stop stop : rest) {
            if (!route.check(stop).feasible()) {
                return null;
            }
            route.append(stop);
        }
        return route;
    }

    /** 이 라우트의 stop 들 (순서대로). */
    private List<Stop> stopsOf(int root) {
        List<Stop> out = new ArrayList<>(sizes[root]);
        for (int at = head[root]; at != -1; at = next[at]) {
            out.add(stops.get(at));
        }
        return out;
    }

    /** 경로 압축만 한다 — 루트는 언제나 «앞쪽 라우트» 여야 {@code head}/{@code tail} 이 맞다. */
    private int find(int i) {
        int root = i;
        while (parent[root] != root) {
            root = parent[root];
        }
        for (int at = i; parent[at] != root; ) {
            int up = parent[at];
            parent[at] = root;
            at = up;
        }
        return root;
    }

    /**
     * 조합별 대표 차량 — 그 조합을 덮는 차량 중 용량이 가장 큰 것.
     *
     * <p>「가장 큰」인 이유는 이 값이 <strong>병합 가능성의 상한</strong>이기 때문이다. 더 작은
     * 차를 기준으로 재면 실을 수 있는 병합을 거절하고, 더 큰 차(조합을 못 덮는 차)를 기준으로
     * 재면 아무 차도 못 싣는 라우트를 만든다.
     */
    private static @Nullable VehicleSpec[] representatives(List<VehicleSpec> vehicles) {
        VehicleSpec[] out = new VehicleSpec[CLASSES.size()];
        Comparator<VehicleSpec> largest = Comparator
                .comparingLong((VehicleSpec spec) -> (long) spec.capacity().maxWeightG()
                        + spec.capacity().maxVolumeCm3())
                // 마지막 키가 id 인 것은 재현성 때문이다 (불변규칙 12).
                .thenComparing(spec -> spec.id().value());
        for (int c = 0; c < CLASSES.size(); c++) {
            ConstraintClass klazz = CLASSES.get(c);
            out[c] = vehicles.stream().filter(klazz::carriedBy).max(largest).orElse(null);
        }
        return out;
    }

    /**
     * 이어 붙여서 아끼는 거리.
     *
     * @param left   작은 쪽 stop 인덱스
     * @param right  큰 쪽 stop 인덱스
     * @param meters {@code d(캠프, left) + d(캠프, right) − d(left, right)}
     */
    private record Saving(int left, int right, long meters) {
    }
}
