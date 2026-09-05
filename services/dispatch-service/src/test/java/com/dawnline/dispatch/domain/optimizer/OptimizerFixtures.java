package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 최적화 단위 테스트의 공통 재료.
 *
 * <p>테스트마다 차량 한 대를 여덟 줄로 만들면 <em>무엇을 보는 테스트인지</em>가 그 여덟 줄에
 * 묻힌다. 여기서는 기본값을 주고, 각 테스트는 자기가 보는 값만 바꾼다.
 */
final class OptimizerFixtures {

    /** 서울시청. 모든 캠프의 기본 위치다. */
    static final GeoPoint CITY_HALL = GeoPoint.of(37.5663, 126.9779);

    /** 강남역. 시청에서 약 8.8 km. */
    static final GeoPoint GANGNAM = GeoPoint.of(37.4979, 127.0276);

    /** 여의도. 시청에서 약 5 km, 강남과는 다른 방향이다. */
    static final GeoPoint YEOUIDO = GeoPoint.of(37.5219, 126.9245);

    static final Instant START = Instant.parse("2026-09-06T01:00:00Z");

    private OptimizerFixtures() {
    }

    static CampDepot depot() {
        return new CampDepot(Ids.newId(), CITY_HALL);
    }

    static WaveRef wave() {
        return new WaveRef(Ids.newId(), Ids.newId(), "SAME_DAY", START);
    }

    /** 여유로운 창 — 시간 관련 룰이 우연히 걸리지 않게 넉넉히 잡는다. */
    static TimeWindow window() {
        return new TimeWindow(START, START.plus(Duration.ofHours(6)));
    }

    static VehicleSpec vehicle() {
        return new VehicleSpec(VehicleId.of(Ids.newId()),
                new Capacity(1_000_000, 5_000_000),
                new VehicleAttrs("VAN", false, false),
                new TimeWindow(START, START.plus(Duration.ofHours(10))),
                VehicleCost.krw(30_000, 500, 200));
    }

    static Stop stop(GeoPoint point) {
        return stop(point, Parcel.EMPTY, 0);
    }

    static Stop stop(GeoPoint point, Parcel parcel, int priority) {
        return new Stop(point, List.of(OrderId.of(Ids.newId())), parcel, window(), 90, priority);
    }

    static Candidate candidate(GeoPoint point) {
        return new Candidate(OrderId.of(Ids.newId()), point,
                new Parcel(1_000, 2_000, false, false), window(), 90, 0);
    }

    /** 도로계수 1.3, 평균 25 km/h — §6.2 의 기본값. */
    static DistanceProvider distance() {
        return new HaversineDistance(1.3d, 25.0d);
    }

    static PlanningProblem problem(RuleSet rules, List<VehicleSpec> vehicles, List<Candidate> candidates) {
        return new PlanningProblem(wave(), depot(), candidates, vehicles, rules, new CostModel(),
                distance(), new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(5)),
                START, 42L);
    }
}
