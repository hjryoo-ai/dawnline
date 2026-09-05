package com.dawnline.dispatch.domain.optimizer.strategy;

import com.dawnline.common.GeoPoint;
import com.dawnline.dispatch.domain.optimizer.CampDepot;
import com.dawnline.dispatch.domain.optimizer.Capacity;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.Stop;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 캠프 기준 극각으로 훑어 연속 구간을 클러스터로 자른다 (DESIGN.md §6.5 2단계).
 *
 * <h2>왜 극각인가</h2>
 * 캠프에서 부챗살처럼 뻗은 구간은 한 차가 돌기 좋다 — 들어갔다 나오는 이동이 겹치지 않기 때문이다.
 * 최근접 이웃만 쓰면 각도를 무시하고 가까운 곳으로만 가다가 반대편으로 건너뛰는 일이 생기고,
 * 그것이 {@code baseline-nn} 이 거리를 낭비하는 방식이다.
 *
 * <h2>자르는 기준은 용량과 <em>목표 크기</em>다</h2>
 * §6.5 는 "용량·max-stops 한도 내에서" 라고 적었는데, {@code max-stops} 는 룰이고 그 파라미터는
 * 룰 인터페이스로 읽을 수 없다(§6.3 — 룰은 데이터다). 대신 <strong>차 한 대 몫</strong>을 목표
 * 크기로 쓴다({@code ceil(stop 수 / 차량 수)}).
 *
 * <p>처음엔 용량으로만 잘랐는데 <strong>그러면 스윕이 아무 일도 하지 않는다</strong>. 1.2 t 트럭은
 * 평균 2.85 kg 화물을 420곳까지 실을 수 있어서, small(458 stop)이 클러스터 한두 개가 됐다.
 * 클러스터 하나가 전체와 같으면 부챗살로 자른 것이 아니고, 실제로 측정에서 거리가 베이스라인의
 * 두 배로 나왔다(669,892 m vs 336,758 m).
 *
 * <h2>권역 경계에서 "우선" 자른다는 것의 뜻</h2>
 * §6.5 2단계는 "권역 경계를 넘을 때는 {@code ZONE_AFFINITY} 페널티를 고려해 자르기 우선" 이다.
 * 이것은 <strong>자를 때가 됐으면 경계에서 자른다</strong>는 뜻이지, 경계마다 자르라는 뜻이 아니다.
 *
 * <p>처음엔 경계마다 잘랐고, 그 결과 클러스터가 30~50개가 됐다(차량은 5대). 남는 클러스터는
 * 이미 실은 차에 얹히고, 그 차의 라우트가 부챗살 여럿을 오가는 지그재그가 된다 — 측정에서
 * 거리가 베이스라인의 두 배로 나왔다. 그래서 <strong>목표 크기의 {@value #ZONE_CUT_THRESHOLD}
 * 배를 넘긴 뒤에만</strong> 경계에서 자른다. 자를 자리를 경계 쪽으로 당기되, 경계가 자르는
 * 이유가 되지는 않게 하는 것이다.
 */
public final class SweepClusterer {

    /** 목표 크기의 이 비율을 넘긴 뒤에야 권역 경계가 자르는 이유가 된다. */
    public static final double ZONE_CUT_THRESHOLD = 0.8d;

    /** 이 수보다 작은 클러스터는 경계에서도 자르지 않는다. 잘게 부수면 차량 수가 모자란다. */
    private final int minStopsBeforeZoneCut;

    /**
     * @param minStopsBeforeZoneCut 권역 경계 자르기를 허용하는 최소 클러스터 크기
     */
    public SweepClusterer(int minStopsBeforeZoneCut) {
        if (minStopsBeforeZoneCut < 1) {
            throw new IllegalArgumentException(
                    "최소 크기는 1 이상이어야 합니다: " + minStopsBeforeZoneCut);
        }
        this.minStopsBeforeZoneCut = minStopsBeforeZoneCut;
    }

    /**
     * 클러스터로 자른다.
     *
     * @param stops       통합된 stop 들
     * @param depot       극각의 기준점
     * @param capacity    가장 큰 차량의 용량. 이보다 큰 클러스터는 어떤 차도 실을 수 없다
     * @param vehicleCount 차량 수. 목표 클러스터 크기를 정하는 데 쓴다
     */
    public List<List<Stop>> cluster(List<Stop> stops, CampDepot depot, Capacity capacity,
            int vehicleCount) {

        Objects.requireNonNull(stops, "stops");
        Objects.requireNonNull(depot, "depot");
        Objects.requireNonNull(capacity, "capacity");
        if (vehicleCount < 1) {
            throw new IllegalArgumentException("차량 수는 1 이상이어야 합니다: " + vehicleCount);
        }
        int targetSize = Math.max(1, Math.ceilDiv(stops.size(), vehicleCount));

        List<Stop> swept = sweep(stops, depot.point());
        List<List<Stop>> clusters = new ArrayList<>();
        List<Stop> current = new ArrayList<>();
        Parcel load = Parcel.EMPTY;
        String zone = null;

        for (Stop stop : swept) {
            Parcel next = load.plus(stop.parcel());
            boolean overCapacity = !capacity.admits(next);
            boolean overTarget = current.size() >= targetSize;
            boolean zoneChanged = zone != null && !zone.equals(stop.zone())
                    && current.size() >= minStopsBeforeZoneCut
                    && current.size() >= targetSize * ZONE_CUT_THRESHOLD;

            if (!current.isEmpty() && (overCapacity || overTarget || zoneChanged)) {
                clusters.add(List.copyOf(current));
                current = new ArrayList<>();
                load = Parcel.EMPTY;
                next = stop.parcel();
            }
            current.add(stop);
            load = next;
            zone = stop.zone();
        }
        if (!current.isEmpty()) {
            clusters.add(List.copyOf(current));
        }
        return List.copyOf(clusters);
    }

    /**
     * 극각 오름차순. 각이 같으면 <strong>가까운 것부터</strong>, 그래도 같으면 입력 순서다 —
     * 정렬이 결정적이어야 같은 seed 가 같은 결과를 낸다(불변규칙 12).
     */
    private List<Stop> sweep(List<Stop> stops, GeoPoint origin) {
        List<Stop> sorted = new ArrayList<>(stops);
        sorted.sort(Comparator
                .comparingDouble((Stop stop) -> angle(origin, stop.point()))
                .thenComparingDouble(stop -> squaredOffset(origin, stop.point())));
        return sorted;
    }

    /** 정북을 0 으로 하는 방위각(라디안). 평면 근사로 충분하다 — 순서만 쓰기 때문이다. */
    private static double angle(GeoPoint origin, GeoPoint point) {
        double east = point.lng() - origin.lng();
        double north = point.lat() - origin.lat();
        double radians = Math.atan2(east, north);
        return radians < 0 ? radians + 2 * Math.PI : radians;
    }

    private static double squaredOffset(GeoPoint origin, GeoPoint point) {
        double east = point.lng() - origin.lng();
        double north = point.lat() - origin.lat();
        return east * east + north * north;
    }
}
