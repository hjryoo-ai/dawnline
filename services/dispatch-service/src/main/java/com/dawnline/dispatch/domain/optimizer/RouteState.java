package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.GeoPoint;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import java.util.Set;

/**
 * 만들어지는 중인 라우트의 상태 (DESIGN.md §6.3 룰 서명의 세 번째 인자).
 *
 * <p>룰은 "이 stop 을 <em>지금 이 라우트에</em> 넣어도 되는가" 를 묻는다. 그래서 차량 스펙만으로는
 * 부족하고 <strong>여기까지 쌓인 사실</strong>이 필요하다 — 누적 적재, stop 수, 현재 시각과 위치,
 * 지나온 권역.
 *
 * <h2>캠프와 거리 제공자를 함께 들고 있는 이유</h2>
 * {@code SHIFT_WINDOW} 는 "복귀 시각 ≤ 근무 종료 − 버퍼" 를 본다. 복귀 구간은 <strong>라우트의
 * 일부</strong>이지 룰의 파라미터가 아니므로, 룰이 캠프와 거리를 인자로 받는 대신 상태에게 물어본다.
 * 같은 이유로 {@code TIME_WINDOW_LIMIT} 은 {@link #arrivalIfAppended} 를 쓴다 — 붙여 보지 않고
 * 도착 시각을 알 수 있어야 배정 전에 판정할 수 있다.
 *
 * <p>불변이다. {@link #append} 는 새 상태를 돌려준다 — 개선 단계(2-opt·relocate)가 여러 후보 배치를
 * 시험해 보고 버리기 때문에, 제자리에서 바뀌는 상태는 되돌리기 코드를 부른다.
 *
 * <h2>붙이기는 O(1) 이다</h2>
 * stop 목록을 배열로 들고 {@code append} 마다 복사하면 붙이기가 O(n) 이 되고, 라우트 하나를
 * 처음부터 다시 만드는 일이 O(n²) 이 된다. 그 재구성은 <strong>탐욕 배정과 국소 탐색의 안쪽
 * 루프</strong>다 — {@code large}(라우트당 약 100 stop)에서 한 번에 수만 번 돈다.
 *
 * <p>그래서 목록 대신 <strong>앞 상태를 가리키는 사슬</strong>로 든다. 붙이기는 노드 하나이고,
 * {@link #stops()} 가 필요할 때 한 번만 펴 준다(라우트를 굳힐 때). 같은 이유로
 * {@code zones} 도 누적해 둔다 — {@code ZONE_AFFINITY} 가 stop 마다 묻는데 그때마다 전체를
 * 훑으면 그것만으로 O(n²) 이다. 권역이 늘 때만 복사하므로 보통은 참조 하나다.
 */
public final class RouteState {

    private final VehicleSpec vehicle;
    private final CampDepot depot;
    private final DistanceProvider distance;
    private final Instant startedAt;
    private final @Nullable RouteState previous;
    private final @Nullable PlannedStop last;
    private final int stopCount;
    private final Set<String> zones;
    private final Parcel load;
    private final GeoPoint at;
    private final Instant time;
    private final int distanceM;

    private RouteState(VehicleSpec vehicle, CampDepot depot, DistanceProvider distance,
            Instant startedAt, @Nullable RouteState previous, @Nullable PlannedStop last,
            int stopCount, Set<String> zones, Parcel load, GeoPoint at, Instant time,
            int distanceM) {

        this.vehicle = vehicle;
        this.depot = depot;
        this.distance = distance;
        this.startedAt = startedAt;
        this.previous = previous;
        this.last = last;
        this.stopCount = stopCount;
        this.zones = zones;
        this.load = load;
        this.at = at;
        this.time = time;
        this.distanceM = distanceM;
    }

    /**
     * 캠프에서 막 출발한 빈 라우트.
     *
     * @param vehicle  차량
     * @param depot    출발·복귀 캠프
     * @param distance 거리 제공자
     * @param startAt  출발 시각
     */
    public static RouteState empty(VehicleSpec vehicle, CampDepot depot, DistanceProvider distance,
            Instant startAt) {

        Objects.requireNonNull(vehicle, "vehicle");
        Objects.requireNonNull(depot, "depot");
        Objects.requireNonNull(distance, "distance");
        Objects.requireNonNull(startAt, "startAt");
        // 근무 시작 전에는 출발하지 않는다 (§6.3 — 근무창은 "이 창 안에서 출발하고 복귀").
        // 이 한 줄이 없으면 SHIFT_WINDOW 는 복귀만 보게 되고, 야간 근무조는 "마감이 늦은
        // 주간 차량" 과 구별되지 않는다 — 새벽 웨이브를 실제로 그 조가 싣는다는 사실이
        // 모델에 없는 상태가 된다 (ADR-030).
        Instant departAt = startAt.isBefore(vehicle.shift().start())
                ? vehicle.shift().start() : startAt;
        return new RouteState(vehicle, depot, distance, departAt, null, null, 0, Set.of(),
                Parcel.EMPTY, depot.point(), departAt, 0);
    }

    /**
     * stop 하나를 끝에 붙인 새 상태.
     *
     * @param stop 붙일 stop
     */
    public RouteState append(Stop stop) {
        Objects.requireNonNull(stop, "stop");
        Travel travel = distance.between(at, stop.point());
        Instant arrival = time.plusSeconds(travel.seconds());
        Instant departure = arrival.plusSeconds(stop.serviceSeconds());
        PlannedStop planned = new PlannedStop(stopCount + 1, stop, arrival, departure);
        return new RouteState(vehicle, depot, distance, startedAt, this, planned, stopCount + 1,
                withZone(stop.zone()), load.plus(stop.parcel()), stop.point(), departure,
                Math.addExact(distanceM, travel.meters()));
    }

    /** 이 stop 을 붙였을 때의 <strong>도착</strong> 시각. 붙이지는 않는다. */
    public Instant arrivalIfAppended(Stop stop) {
        Objects.requireNonNull(stop, "stop");
        return time.plusSeconds(distance.between(at, stop.point()).seconds());
    }

    /** 지금 캠프로 돌아간다면 도착하는 시각. */
    public Instant returnTime() {
        return time.plusSeconds(distance.between(at, depot.point()).seconds());
    }

    /** 이 stop 을 붙인 뒤 캠프로 돌아간다면 도착하는 시각 ({@code SHIFT_WINDOW}). */
    public Instant returnTimeIfAppended(Stop stop) {
        Objects.requireNonNull(stop, "stop");
        Instant departure = arrivalIfAppended(stop).plusSeconds(stop.serviceSeconds());
        return departure.plusSeconds(distance.between(stop.point(), depot.point()).seconds());
    }

    /** 캠프 복귀분을 포함한 총 이동 거리(m). {@code PlannedRoute.distanceM} 이 되는 값이다. */
    public int distanceWithReturn() {
        return Math.addExact(distanceM, distance.between(at, depot.point()).meters());
    }

    /** 캠프 복귀까지의 총 소요 시간(초). 이동 + 서비스를 모두 포함한다. */
    public int durationWithReturn() {
        return (int) java.time.Duration.between(startedAt, returnTime()).toSeconds();
    }

    /** 이 라우트의 차량. */
    public VehicleSpec vehicle() {
        return vehicle;
    }

    /** 출발·복귀 캠프. */
    public CampDepot depot() {
        return depot;
    }

    /**
     * 여기까지 배치된 stop 들 (방문 순서).
     *
     * <p>사슬을 펴는 일이라 O(n) 이고 <strong>매번 새로 만든다</strong>. 라우트를 굳힐 때
     * 한 번 부르는 값이지 룰이 stop 마다 묻는 값이 아니다 — 룰이 보는 것은
     * {@link #stopCount()}·{@link #zones()}·{@link #load()} 이고 전부 O(1) 이다.
     */
    public List<PlannedStop> stops() {
        PlannedStop[] out = new PlannedStop[stopCount];
        RouteState node = this;
        for (int i = stopCount - 1; i >= 0; i--) {
            out[i] = node.last;
            node = node.previous;
        }
        return List.of(out);
    }

    /** 배치된 stop 수 (§6.3 {@code MAX_STOPS_PER_ROUTE}). */
    public int stopCount() {
        return stopCount;
    }

    /** 누적 적재 (§6.3 {@code VEHICLE_CAPACITY}). */
    public Parcel load() {
        return load;
    }

    /** 현재 위치. 아직 아무 데도 안 갔으면 캠프다. */
    public GeoPoint at() {
        return at;
    }

    /** 현재 시각 — 마지막 stop 의 <em>출발</em> 시각이다. */
    public Instant time() {
        return time;
    }

    /** 라우트 출발 시각. */
    public Instant startedAt() {
        return startedAt;
    }

    /** 누적 이동 거리(m). 캠프 복귀분은 포함하지 않는다 — {@link #distanceWithReturn} 이 그 값이다. */
    public int distanceM() {
        return distanceM;
    }

    /** 지나온 권역들 (§6.3 {@code ZONE_AFFINITY}). 방문 순서를 유지한다. */
    public Set<String> zones() {
        return zones;
    }

    /** 이 권역을 더한 집합. 이미 있으면 <strong>같은 참조</strong>다 — 보통의 경우다. */
    private Set<String> withZone(String zone) {
        if (zones.contains(zone)) {
            return zones;
        }
        Set<String> next = new LinkedHashSet<>(zones);
        next.add(zone);
        return java.util.Collections.unmodifiableSet(next);
    }

    /** 배치된 stop 이 하나도 없는가. 비어 있는 라우트는 차량 고정비를 물지 않는다 (§6.4). */
    public boolean isEmpty() {
        return stopCount == 0;
    }
}
