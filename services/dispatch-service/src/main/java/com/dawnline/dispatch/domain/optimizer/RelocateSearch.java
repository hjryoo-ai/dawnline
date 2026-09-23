package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.GeoPoint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 부분 재계획의 탐색 — {@code relocate} 만 (DESIGN.md §6.8 3단계, ADR-048 결정 4).
 *
 * <h2>왜 {@link com.dawnline.dispatch.domain.optimizer.strategy.LocalSearchImprover} 가 아닌가</h2>
 * 저쪽은 <strong>전체 계획</strong>을 개선한다 — 모든 라우트의 순서와 소속을 자유롭게 바꾼다.
 * 여기서는 둘이 다르다. ① 이미 지나온 자리(<em>얼어 있는 앞자락</em>)는 건드릴 수 없고,
 * ② 바꾸는 것은 <strong>위험한 라우트에서 나가는 이동뿐</strong>이다. 그 둘을 저쪽에 인자로
 * 밀어 넣으면 「개선기」가 「재계획기」의 규칙을 알게 되고, 같은 규칙이 두 곳에 산다.
 *
 * <h2>시계는 밀려 있다</h2>
 * {@link RouteInput#startAt} 은 <strong>계획 시작 + 편차</strong>다(§6.8). 그래야 남은 stop 의
 * 도착 시각이 현실에 가깝고, 그래야 지각 페널티가 나타나 「옮기면 나아지는가」에 답이 생긴다.
 * 계획 시계로 재면 아무것도 늦지 않아 이 탐색은 언제나 「이득 없음」을 돌려준다.
 *
 * <h2>후보를 재는 방법 — 다시 넣어 본다</h2>
 * {@code LocalSearchImprover} 와 같은 규칙이다: 근사식으로 재지 않고 그 순서로 라우트를 다시
 * 만들어 하드 룰과 비용을 본다. 근사식은 룰과 갈라지고, 갈라지는 순간 「왜 이 차인가」에 답할 수
 * 없다(§6.3). 값이 되는 대가는 <strong>삽입 위치 선별</strong>로 치른다 — 위치마다 라우트를 다시
 * 만들지 않고 거리로 가장 싼 자리 하나를 고른 뒤 그 자리만 제대로 잰다.
 *
 * <h2>하드 룰은 «받는 쪽» 에만 돌린다</h2>
 * 짐이 줄어드는 변경은 룰을 어길 수 없다 — {@code CancelOrderService} 가 같은 이유로
 * {@code check()} 를 부르지 않는다. 받는 쪽은 다르다: 용량·근무창·냉장·위험물이 거기서 깨진다.
 * [ADR-039] 의 제약 조합이 relocate 에도 걸리는 자리가 <strong>이 한 줄</strong>이다 —
 * {@code VEHICLE_ATTRIBUTE_MATCH} 가 {@link RuleSet#check} 안에 있다.
 */
public final class RelocateSearch {

    /** 한 번의 재계획이 옮기는 stop 수 상한 (§6.8 — 대규모 재편 금지). */
    public static final int MAX_MOVES = 5;

    /**
     * 한 번의 재계획이 라우트를 다시 만들어 보는 횟수 상한.
     *
     * <p>「대규모 재편 금지」는 기사의 문장이면서 성능의 문장이기도 하다. 이 값이 없으면
     * {@code peak}(라우트당 최대 90 stop · 계획당 라우트 수십)에서 한 번의 at-risk 가 소비 스레드를
     * 수 초간 잡는다 — 그리고 at-risk 는 <em>가장 바쁠 때</em> 온다.
     */
    public static final int MAX_EVALUATIONS = 2_000;

    private final DistanceProvider distance;
    private final CostModel cost;

    /**
     * @param distance 거리 제공자
     * @param cost     비용 산식
     */
    public RelocateSearch(DistanceProvider distance, CostModel cost) {
        this.distance = Objects.requireNonNull(distance, "distance");
        this.cost = Objects.requireNonNull(cost, "cost");
    }

    /**
     * 라우트 하나 — 지금 상태 그대로.
     *
     * @param routeId 라우트 id
     * @param vehicle 차량
     * @param depot   출발·복귀 캠프
     * @param stops   방문 순서대로의 stop 들 (취소된 것은 이미 빠져 있다)
     * @param frozen  앞에서부터 <strong>얼어 있는</strong> stop 수. 기사가 이미 닿은 자리이고,
     *                빼지도 그 사이에 넣지도 않는다 (ADR-048 결정 4 (a))
     * @param startAt <strong>평가 시계</strong> — 계획 시작 + 그 라우트의 편차
     */
    public record RouteInput(UUID routeId, VehicleSpec vehicle, CampDepot depot, List<Stop> stops,
            int frozen, Instant startAt) {

        public RouteInput {
            Objects.requireNonNull(routeId, "routeId");
            Objects.requireNonNull(vehicle, "vehicle");
            Objects.requireNonNull(depot, "depot");
            Objects.requireNonNull(startAt, "startAt");
            stops = List.copyOf(Objects.requireNonNull(stops, "stops"));
            if (frozen < 0 || frozen > stops.size()) {
                throw new IllegalArgumentException(
                        "얼어 있는 앞자락이 stop 수를 넘습니다: frozen=%d, stops=%d"
                                .formatted(frozen, stops.size()));
            }
        }
    }

    /**
     * 옮긴 것 하나.
     *
     * @param fromRouteId 떠난 라우트
     * @param toRouteId   받은 라우트
     * @param orderIds    함께 옮긴 주문들 — stop 단위로 옮기므로 그 stop 의 주문 전부다
     * @param gainKrw     이 이동이 줄인 <strong>두 라우트 합</strong>의 비용. 양수다
     */
    public record Move(UUID fromRouteId, UUID toRouteId, List<OrderId> orderIds, long gainKrw) {

        public Move {
            orderIds = List.copyOf(Objects.requireNonNull(orderIds, "orderIds"));
        }
    }

    /**
     * 탐색 결과.
     *
     * @param moves     적용할 이동들 (적용 순서). 비어 있으면 이득이 없었다
     * @param sequences 달라진 라우트들의 <strong>최종 방문 순서</strong>. 저장이 이 순서를 쓴다
     * @param gainKrw   줄인 비용의 합
     */
    public record Outcome(List<Move> moves, Map<UUID, List<Stop>> sequences, long gainKrw) {

        public Outcome {
            moves = List.copyOf(Objects.requireNonNull(moves, "moves"));
            sequences = Map.copyOf(Objects.requireNonNull(sequences, "sequences"));
        }

        /** 옮길 것이 있었는가. */
        public boolean moved() {
            return !moves.isEmpty();
        }
    }

    /**
     * {@code source} 에서 {@code candidates} 로 나가는 이동만 찾는다.
     *
     * <p>「가장 좋은 이동 하나를 적용하고 다시 본다」를 {@link #MAX_MOVES} 번까지 반복한다.
     * 첫 개선이 아니라 <em>최선</em>을 고르는 이유: 이동 하나하나가 기사에게 보이는 변화라
     * 횟수가 적을수록 좋고, 그러려면 한 번에 가장 값이 큰 것을 골라야 한다.
     *
     * @param rules      캠프의 룰 묶음. 계획이 쓴 것과 같은 스냅샷이다 (§6.3)
     * @param source     위험한 라우트 — 여기서만 뺀다
     * @param candidates 받을 수 있는 라우트들. {@code source} 는 들어 있지 않아야 한다
     * @return 이동이 없으면 빈 결과
     */
    public Outcome search(RuleSet rules, RouteInput source, List<RouteInput> candidates) {
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(candidates, "candidates");

        Map<UUID, List<Stop>> current = new LinkedHashMap<>();
        current.put(source.routeId(), new ArrayList<>(source.stops()));
        Map<UUID, RouteInput> byId = new LinkedHashMap<>();
        byId.put(source.routeId(), source);
        for (RouteInput candidate : candidates) {
            if (candidate.routeId().equals(source.routeId())) {
                throw new IllegalArgumentException("원 라우트는 후보가 아닙니다: " + source.routeId());
            }
            current.put(candidate.routeId(), new ArrayList<>(candidate.stops()));
            byId.put(candidate.routeId(), candidate);
        }

        List<Move> moves = new ArrayList<>();
        Map<UUID, List<Stop>> changed = new LinkedHashMap<>();
        long total = 0L;
        int evaluations = 0;

        for (int round = 0; round < MAX_MOVES && evaluations < MAX_EVALUATIONS; round++) {
            Best best = null;
            List<Stop> from = current.get(source.routeId());
            for (int index = source.frozen(); index < from.size(); index++) {
                for (RouteInput target : candidates) {
                    if (evaluations >= MAX_EVALUATIONS) {
                        break;
                    }
                    evaluations++;
                    Best move = evaluate(rules, byId.get(source.routeId()), from, index, target,
                            current.get(target.routeId()));
                    if (move != null && (best == null || move.gain() > best.gain())) {
                        best = move;
                    }
                }
            }
            if (best == null) {
                break;                                  // 국소 최적 — 더 줄일 이동이 없다
            }
            List<Stop> shrunk = current.get(source.routeId());
            Stop moved = shrunk.remove(best.index());
            current.get(best.targetId()).add(best.insertAt(), moved);
            changed.put(source.routeId(), List.copyOf(shrunk));
            changed.put(best.targetId(), List.copyOf(current.get(best.targetId())));
            moves.add(new Move(source.routeId(), best.targetId(), moved.orderIds(), best.gain()));
            total += best.gain();
        }
        return new Outcome(moves, changed, total);
    }

    /**
     * {@code from} 의 {@code index} 번 stop 을 {@code target} 으로 옮겨 보고 값을 잰다.
     *
     * @return 이득이 없거나 받는 쪽이 하드 룰을 어기면 {@code null}
     */
    private @Nullable Best evaluate(RuleSet rules, RouteInput source, List<Stop> from, int index,
            RouteInput target, List<Stop> to) {

        Stop stop = from.get(index);
        if (to.stream().anyMatch(other -> other.point().equals(stop.point()))) {
            // 받는 쪽에 이미 같은 지점이 있다. 옮기면 그 둘은 «합쳐져야» 하는데(§6.5 1단계),
            // 통합 키는 지점만이 아니라 약속창과 제약 조합까지 본다 — 창이 다르면 합칠 수
            // 없고, 합치지 않으면 한 라우트가 같은 건물을 두 번 방문한다. 재계획에는 다시
            // 통합할 경로가 없으므로 이 자리는 비워 둔다 (ADR-048 재검토 지점).
            return null;
        }
        int insertAt = cheapestPosition(target, to, stop);

        List<Stop> shrunk = new ArrayList<>(from);
        shrunk.remove(index);
        List<Stop> grown = new ArrayList<>(to);
        grown.add(insertAt, stop);

        Long grownCost = costOf(rules, target, grown, true);
        if (grownCost == null) {
            return null;                                // 받는 쪽이 용량·근무창·냉장을 어긴다
        }
        long before = costOf(rules, source, from, false) + costOf(rules, target, to, false);
        long after = costOf(rules, source, shrunk, false) + grownCost;
        long gain = before - after;
        return gain > 0L ? new Best(index, target.routeId(), insertAt, gain) : null;
    }

    /**
     * 거리만으로 가장 싼 삽입 자리 — <strong>얼어 있는 앞자락 뒤</strong>에서만 고른다.
     *
     * <p>위치마다 라우트를 다시 만들면 stop 수만큼 비용이 곱해진다. 끊는 간선과 잇는 간선만
     * 재는 것은 근사이고, 그 대가는 <em>지각 페널티가 크게 주는 먼 자리</em>를 보지 못하는
     * 것이다 — {@code LocalSearchImprover} 의 거리 선별과 같은 대가이고 같은 이유로 진다.
     */
    private int cheapestPosition(RouteInput target, List<Stop> stops, Stop stop) {
        int best = target.frozen();
        long bestAdded = Long.MAX_VALUE;
        for (int position = target.frozen(); position <= stops.size(); position++) {
            GeoPoint before = position == 0
                    ? target.depot().point() : stops.get(position - 1).point();
            GeoPoint after = position == stops.size()
                    ? target.depot().point() : stops.get(position).point();
            long added = distance.between(before, stop.point()).meters()
                    + distance.between(stop.point(), after).meters()
                    - distance.between(before, after).meters();
            if (added < bestAdded) {
                bestAdded = added;
                best = position;
            }
        }
        return best;
    }

    /**
     * 이 순서로 라우트를 다시 만들었을 때의 비용.
     *
     * @param checked 하드 룰을 돌리는가. 받는 쪽만 참이다
     * @return 빈 라우트는 0. {@code checked} 이고 룰을 어기면 {@code null}
     */
    private @Nullable Long costOf(RuleSet rules, RouteInput route, List<Stop> stops,
            boolean checked) {

        if (stops.isEmpty()) {
            return 0L;                                  // 굴리지 않은 차는 고정비를 물지 않는다
        }
        RouteAccumulator accumulator = new RouteAccumulator(rules, route.vehicle(), route.depot(),
                distance, route.startAt());
        for (Stop stop : stops) {
            if (checked && !accumulator.check(stop).feasible()) {
                return null;
            }
            accumulator.append(stop);
        }
        return accumulator.toRoute(cost).cost().krw();
    }

    /** 지금까지 가장 좋은 이동. */
    private record Best(int index, UUID targetId, int insertAt, long gain) {
    }
}
