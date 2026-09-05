package com.dawnline.dispatch.domain.optimizer.strategy;

import com.dawnline.common.Money;
import com.dawnline.dispatch.domain.optimizer.CampDepot;
import com.dawnline.dispatch.domain.optimizer.CostModel;
import com.dawnline.dispatch.domain.optimizer.DistanceProvider;
import com.dawnline.dispatch.domain.optimizer.Feasibility;
import com.dawnline.dispatch.domain.optimizer.RouteAccumulator;
import com.dawnline.dispatch.domain.optimizer.Stop;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 클러스터를 한계비용이 가장 작은 차량에 붙인다 (DESIGN.md §6.5 3단계).
 *
 * <h2>한계비용</h2>
 * "이 클러스터를 이 차에 <em>더</em> 실으면 총비용이 얼마나 오르는가" 다. 이미 반쯤 찬 차에
 * 근처 클러스터를 얹는 것이, 새 차를 꺼내 고정비를 무는 것보다 싼 경우가 많다 — 그 판단이
 * 고정비를 실제로 아끼는 자리다.
 *
 * <p>재는 방법은 <strong>실제로 넣어 보는 것</strong>이다. {@link RouteAccumulator#branch()} 로
 * 사본을 만들어 시퀀싱까지 돌리고 비용 차이를 본다. 근사식을 쓰면 그 식이 룰과 갈라지고,
 * 갈라지는 순간 "왜 이 차인가" 에 답할 수 없게 된다.
 *
 * <h2>빈 차를 먼저 본다</h2>
 * §6.5 3단계는 "클러스터를 차량에 배정" 이고, 클러스터는 차 한 대 몫으로 잘려 있다. 그래서
 * <strong>빈 차가 있으면 빈 차에 준다</strong> — 이미 실은 차들 중에서만 고르면 고정비를 아끼려고
 * 한 대에 계속 얹게 되고, 그 차의 라우트가 부챗살 여럿을 오가는 지그재그가 된다.
 *
 * <p>측정이 그 값을 보여 줬다. 빈 차 우선이 없을 때 small 에서 거리가 베이스라인의 두 배
 * (669,892 m vs 336,758 m), 미배정이 35건(베이스라인 9건)이었다. 긴 라우트가 근무창을 태워
 * 뒤의 stop 이 갈 곳을 잃기 때문이다 — <strong>탐욕이 미래의 미배정 비용을 못 본다.</strong>
 * 클러스터를 차 한 대 몫으로 자른 것이 그 근시안을 구조로 막으려는 장치이고, 빈 차 우선이 그
 * 장치를 실제로 작동시킨다.
 *
 * <h2>실을 차가 없으면 반으로 쪼갠다</h2>
 * §6.5 3단계 그대로다. 클러스터가 {@code max-stops} 를 넘거나 어떤 차의 용량에도 안 맞을 때,
 * 반으로 나누면 들어갈 수 있다. 더 쪼갤 수 없는 stop 하나가 여전히 안 들어가면 그때 미배정이다.
 */
public final class GreedyAssigner {

    private final NearestNeighborSequencer sequencer;

    /**
     * @param sequencer 클러스터 안의 방문 순서를 정하는 시퀀서
     */
    public GreedyAssigner(NearestNeighborSequencer sequencer) {
        this.sequencer = Objects.requireNonNull(sequencer, "sequencer");
    }

    /**
     * 배정한다.
     *
     * @param clusters  클러스터들
     * @param routes    차량별 라우트 (호출부가 미리 만들어 둔다)
     * @param depot     캠프
     * @param distance  거리 제공자
     * @param cost      비용 산식
     * @param startedAt 계획 시작 시각
     * @return 어떤 차에도 들어가지 못한 stop 들
     */
    public List<Stop> assign(List<List<Stop>> clusters, List<RouteAccumulator> routes,
            CampDepot depot, DistanceProvider distance, CostModel cost, Instant startedAt,
            Map<Stop, Feasibility> refusals) {

        // 가장 이른 약속 마감 순으로 — 시간이 급한 것부터 자리를 잡아야 지각이 준다 (§6.5 3단계).
        List<List<Stop>> ordered = new ArrayList<>(clusters);
        ordered.sort(Comparator.comparing(GreedyAssigner::earliestDeadline));

        List<Stop> unassigned = new ArrayList<>();
        for (List<Stop> cluster : ordered) {
            unassigned.addAll(place(cluster, routes, distance, cost, refusals));
        }
        unassigned.forEach(stop -> refusals.putIfAbsent(stop, lastRefusalFor(stop, routes)));
        return List.copyOf(unassigned);
    }

    /** 이 stop 을 마지막으로 거절한 사유. 설명(§6.3)이 "실을 차가 없다" 로만 남지 않게 한다. */
    private Feasibility lastRefusalFor(Stop stop, List<RouteAccumulator> routes) {
        return routes.stream()
                .map(route -> route.check(stop))
                .filter(feasibility -> !feasibility.feasible())
                .reduce((first, second) -> second)
                .orElse(Feasibility.violated("no-feasible-vehicle", "실을 수 있는 차량이 없습니다"));
    }

    private List<Stop> place(List<Stop> cluster, List<RouteAccumulator> routes,
            DistanceProvider distance, CostModel cost, Map<Stop, Feasibility> refusals) {

        // 빈 차를 먼저 본다. 규모가 커질수록 이쪽이 낫다 — 측정: large 에서 빈 차 우선
        // 15,904,839 vs 전체 비교 17,103,847, 계획 시간도 5.5초 vs 10.8초다. medium 에서는
        // 반대로 전체 비교가 조금 낫지만(5,093,679 vs 5,505,443) 차이가 8%이고, large 쪽 차이가
        // 7%에 계획 시간이 두 배라 큰 쪽을 기준으로 골랐다. 이 선택은 개선 단계(Phase 4)가
        // 들어오면 다시 재야 한다 — 지그재그를 뒤에서 펴 주면 전제가 바뀐다.
        List<RouteAccumulator> empty = routes.stream().filter(RouteAccumulator::isEmpty).toList();
        List<Stop> result = tryOn(empty, cluster, distance, cost);
        if (result != null) {
            return result.isEmpty() ? List.of()
                    : splitOrGiveUp(result, routes, distance, cost, refusals);
        }
        List<Stop> onLoaded = tryOn(
                routes.stream().filter(route -> !route.isEmpty()).toList(), cluster, distance, cost);
        if (onLoaded != null) {
            return onLoaded.isEmpty() ? List.of()
                    : splitOrGiveUp(onLoaded, routes, distance, cost, refusals);
        }
        return splitOrGiveUp(cluster, routes, distance, cost, refusals);
    }

    /** 이 후보 차량들에 실어 본다. 하나도 못 실으면 {@code null}. */
    private List<Stop> tryOn(List<RouteAccumulator> routes, List<Stop> cluster,
            DistanceProvider distance, CostModel cost) {

        Map<RouteAccumulator, Trial> trials = new LinkedHashMap<>();
        for (RouteAccumulator route : routes) {
            RouteAccumulator trial = route.branch();
            var leftover = sequencer.sequence(trial, cluster, distance);
            if (leftover.size() == cluster.size()) {
                continue;                       // 한 개도 못 넣었다 — 이 차는 후보가 아니다
            }
            long marginal = trial.toRoute(cost).cost().krw() - currentCost(route, cost);
            trials.put(route, new Trial(trial, leftover.size(), marginal));
        }

        Trial best = trials.values().stream()
                // 많이 넣는 쪽이 먼저다 — 절반만 넣고 싼 차보다 전부 넣는 차가 낫다.
                .min(Comparator.comparingInt(Trial::leftover).thenComparingLong(Trial::marginalKrw))
                .orElse(null);
        if (best == null) {
            return null;
        }

        // 시험 배치를 확정한다 — 같은 순서로 실제 라우트에 다시 넣는다.
        RouteAccumulator target = trials.entrySet().stream()
                .filter(entry -> entry.getValue() == best).findFirst().orElseThrow().getKey();
        return NearestNeighborSequencer.asList(sequencer.sequence(target, cluster, distance));
    }

    /** 반으로 쪼개 다시 시도한다. 하나짜리는 더 쪼갤 수 없으므로 미배정이다. */
    private List<Stop> splitOrGiveUp(List<Stop> cluster, List<RouteAccumulator> routes,
            DistanceProvider distance, CostModel cost, Map<Stop, Feasibility> refusals) {

        if (cluster.size() <= 1) {
            return cluster;
        }
        int half = cluster.size() / 2;
        List<Stop> left = place(cluster.subList(0, half), routes, distance, cost, refusals);
        List<Stop> right = place(cluster.subList(half, cluster.size()), routes, distance, cost, refusals);
        List<Stop> unassigned = new ArrayList<>(left);
        unassigned.addAll(right);
        return unassigned;
    }

    private long currentCost(RouteAccumulator route, CostModel cost) {
        return route.isEmpty() ? 0L : route.toRoute(cost).cost().krw();
    }

    private static Instant earliestDeadline(List<Stop> cluster) {
        return cluster.stream().map(stop -> stop.promised().end())
                .min(Comparator.naturalOrder()).orElseThrow();
    }

    /**
     * 시험 배치 결과.
     *
     * @param route       사본
     * @param leftover    넣지 못한 stop 수
     * @param marginalKrw 이 배치로 오르는 비용
     */
    private record Trial(RouteAccumulator route, int leftover, long marginalKrw) {
    }
}
