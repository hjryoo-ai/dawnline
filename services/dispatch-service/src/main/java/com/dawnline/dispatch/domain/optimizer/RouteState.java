package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.GeoPoint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 만들어지는 중인 라우트의 상태 (DESIGN.md §6.3 룰 서명의 세 번째 인자).
 *
 * <p>룰은 "이 stop 을 <em>지금 이 라우트에</em> 넣어도 되는가" 를 묻는다. 그래서 차량 스펙만으로는
 * 부족하고 <strong>여기까지 쌓인 사실</strong>이 필요하다 — 누적 적재, stop 수, 현재 시각과 위치,
 * 지나온 권역.
 *
 * <p>불변이다. {@link #append} 는 새 상태를 돌려준다 — 개선 단계(2-opt·relocate)가 여러 후보 배치를
 * 시험해 보고 버리기 때문에, 제자리에서 바뀌는 상태는 되돌리기 코드를 부른다.
 */
public final class RouteState {

    private final VehicleSpec vehicle;
    private final List<PlannedStop> stops;
    private final Parcel load;
    private final GeoPoint at;
    private final Instant time;
    private final int distanceM;

    private RouteState(VehicleSpec vehicle, List<PlannedStop> stops, Parcel load, GeoPoint at,
            Instant time, int distanceM) {
        this.vehicle = vehicle;
        this.stops = stops;
        this.load = load;
        this.at = at;
        this.time = time;
        this.distanceM = distanceM;
    }

    /**
     * 캠프에서 막 출발한 빈 라우트.
     *
     * @param vehicle 차량
     * @param depot   출발 캠프
     * @param startAt 출발 시각. 근무창 시작 이후여야 한다
     */
    public static RouteState empty(VehicleSpec vehicle, CampDepot depot, Instant startAt) {
        Objects.requireNonNull(vehicle, "vehicle");
        Objects.requireNonNull(depot, "depot");
        Objects.requireNonNull(startAt, "startAt");
        return new RouteState(vehicle, List.of(), Parcel.EMPTY, depot.point(), startAt, 0);
    }

    /**
     * stop 하나를 끝에 붙인 새 상태.
     *
     * @param stop    붙일 stop
     * @param travel  현재 위치에서 그 stop 까지의 이동
     */
    public RouteState append(Stop stop, Travel travel) {
        Objects.requireNonNull(stop, "stop");
        Objects.requireNonNull(travel, "travel");
        Instant arrival = time.plusSeconds(travel.seconds());
        Instant departure = arrival.plusSeconds(stop.serviceSeconds());
        List<PlannedStop> next = new ArrayList<>(stops);
        next.add(new PlannedStop(stops.size() + 1, stop, arrival, departure));
        return new RouteState(vehicle, List.copyOf(next), load.plus(stop.parcel()), stop.point(),
                departure, Math.addExact(distanceM, travel.meters()));
    }

    /** 이 라우트의 차량. */
    public VehicleSpec vehicle() {
        return vehicle;
    }

    /** 여기까지 배치된 stop 들 (방문 순서). */
    public List<PlannedStop> stops() {
        return stops;
    }

    /** 배치된 stop 수 (§6.3 {@code MAX_STOPS_PER_ROUTE}). */
    public int stopCount() {
        return stops.size();
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

    /** 누적 이동 거리(m). 캠프 복귀분은 아직 포함하지 않는다. */
    public int distanceM() {
        return distanceM;
    }

    /** 지나온 권역들 (§6.3 {@code ZONE_AFFINITY}). 방문 순서를 유지한다. */
    public Set<String> zones() {
        Set<String> zones = new LinkedHashSet<>();
        stops.forEach(planned -> zones.add(planned.stop().zone()));
        return zones;
    }

    /** 배치된 stop 이 하나도 없는가. 비어 있는 라우트는 차량 고정비를 물지 않는다 (§6.4). */
    public boolean isEmpty() {
        return stops.isEmpty();
    }
}
