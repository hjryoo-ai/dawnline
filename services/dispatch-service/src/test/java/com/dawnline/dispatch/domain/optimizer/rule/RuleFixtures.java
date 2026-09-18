package com.dawnline.dispatch.domain.optimizer.rule;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.domain.optimizer.CampDepot;
import com.dawnline.dispatch.domain.optimizer.Capacity;
import com.dawnline.dispatch.domain.optimizer.DistanceProvider;
import com.dawnline.dispatch.domain.optimizer.HaversineDistance;
import com.dawnline.dispatch.domain.optimizer.OrderId;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.RouteState;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleAttrs;
import com.dawnline.dispatch.domain.optimizer.VehicleCost;
import com.dawnline.dispatch.domain.optimizer.VehicleId;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 룰 평가기 테스트의 공통 재료. 각 테스트는 자기가 보는 값만 바꾼다. */
final class RuleFixtures {

    static final GeoPoint CITY_HALL = GeoPoint.of(37.5663, 126.9779);
    static final GeoPoint GANGNAM = GeoPoint.of(37.4979, 127.0276);
    static final GeoPoint YEOUIDO = GeoPoint.of(37.5219, 126.9245);
    static final Instant START = Instant.parse("2026-09-06T01:00:00Z");
    static final TimeWindow PROMISED = new TimeWindow(START, START.plus(Duration.ofHours(4)));

    private RuleFixtures() {
    }

    static DistanceProvider distance() {
        return new HaversineDistance(1.3d, 25.0d);
    }

    static CampDepot depot() {
        return new CampDepot(Ids.newId(), CITY_HALL);
    }

    static VehicleSpec vehicle() {
        return vehicle(new VehicleAttrs("VAN", false, false));
    }

    static VehicleSpec vehicle(VehicleAttrs attrs) {
        return new VehicleSpec(VehicleId.of(Ids.newId()), new Capacity(1_000_000, 5_000_000), attrs,
                new TimeWindow(START, START.plus(Duration.ofHours(10))),
                VehicleCost.krw(30_000, 500, 200));
    }

    static VehicleSpec vehicleWithShiftEnd(Instant end) {
        return new VehicleSpec(VehicleId.of(Ids.newId()), new Capacity(1_000_000, 5_000_000),
                new VehicleAttrs("VAN", false, false), new TimeWindow(START, end),
                VehicleCost.krw(30_000, 500, 200));
    }

    static Stop stop(GeoPoint point) {
        return stop(point, Parcel.EMPTY, 0, 1);
    }

    static Stop stop(GeoPoint point, Parcel parcel, int priority, int orderCount) {
        List<OrderId> ids = java.util.stream.IntStream.range(0, orderCount)
                .mapToObj(i -> OrderId.of(Ids.newId())).toList();
        return new Stop(point, ids, parcel, PROMISED, 90, priority);
    }

    static Stop stopPromised(GeoPoint point, TimeWindow promised) {
        return new Stop(point, List.of(OrderId.of(Ids.newId())), Parcel.EMPTY, promised, 90, 0);
    }

    /**
     * 그 자리에서 <strong>시간을 쓰지 않는</strong> stop — 순번만 올리고 시각은 그대로 둔다.
     * 「순번이 아니라 시각으로 잰다」를 보는 테스트가 두 축을 떼어 놓는 데 쓴다([ADR-040]).
     */
    static Stop weightlessStop(GeoPoint point) {
        return new Stop(point, List.of(OrderId.of(Ids.newId())), Parcel.EMPTY, PROMISED, 0, 0);
    }

    /** 근무가 계획보다 <strong>늦게</strong> 시작하는 차량. 라우트 출발이 그쪽으로 밀린다. */
    static VehicleSpec vehicleWithShiftStart(Instant start) {
        return new VehicleSpec(VehicleId.of(Ids.newId()), new Capacity(1_000_000, 5_000_000),
                new VehicleAttrs("VAN", false, false),
                new TimeWindow(start, start.plus(Duration.ofHours(10))),
                VehicleCost.krw(30_000, 500, 200));
    }

    static RouteState emptyRoute(VehicleSpec vehicle) {
        return RouteState.empty(vehicle, depot(), distance(), START);
    }

    static RuleDefinition definition(String name, RuleType type, int priority,
            Map<String, Object> params) {
        return new RuleDefinition(name, type, type.severity(), priority, params);
    }

    /**
     * 그 타입의 <strong>유효한</strong> 정의 하나. 타입을 도는 검사들이 함께 쓴다 — 파라미터가
     * 한 곳에만 있어야 새 타입이 생겼을 때 고칠 자리가 하나다.
     */
    static RuleDefinition definitionFor(RuleType type) {
        String name = type.name().toLowerCase(java.util.Locale.ROOT);
        Map<String, Object> params = switch (type) {
            case VEHICLE_ATTRIBUTE_MATCH ->
                    Map.of("orderFlag", "requiresCold", "vehicleFlag", "isCold");
            case VEHICLE_CAPACITY -> Map.of();
            case MAX_STOPS_PER_ROUTE -> Map.of("max", 120);
            case SHIFT_WINDOW -> Map.of("bufferMinutes", 30);
            case TIME_WINDOW_LIMIT -> Map.of("hardLimitMinutes", 60);
            case TIME_WINDOW_PENALTY -> Map.of("penaltyPerMinuteKrw", 50);
            case ZONE_AFFINITY -> Map.of("crossZonePenaltyKrw", 2_000);
            case PRIORITY_BOOST -> Map.of("bonusKrw", 3_000, "halfLifeMinutes", 12);
            case VEHICLE_PREFERENCE ->
                    Map.of("preferredTypes", List.of("VAN"), "penaltyKrw", 4_000);
            case UNASSIGNED_PENALTY -> Map.of("baseKrw", 30_000, "perPriorityKrw", 20_000);
        };
        return definition(name, type, 10, params);
    }
}
