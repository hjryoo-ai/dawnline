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
 * <p><strong>목표 클러스터 수는 총수요/용량에서 나온다</strong>(2026-09-05 정정). 한동안
 * {@code ceil(stop수 / 차량수)} 로 잡았는데, 그러면 클러스터 수가 <em>차량 수와 같아지고</em>
 * 뒤의 탐욕 배정이 클러스터마다 새 차를 열어 <strong>언제나 전 차량을 굴린다</strong>.
 * 측정이 그것을 보여 줬다 — sweep 이 5/5 · 20/20 · 40/40 을 쓸 때 baseline 은 4/5 · 14/20 · 31/40
 * 이었고, 고정비 차이가 총비용 격차의 31~96%였다(`docs/benchmarks/phase3-baseline.md`).
 *
 * <p>차 한 대가 실을 수 있는 양은 <em>용량</em>이지 "전체를 차량 수로 나눈 값" 이 아니다.
 * 그래서 총수요를 가장 큰 차량의 용량으로 나눠 필요한 최소 클러스터 수를 구하고, 그보다 잘게
 * 자르지 않는다. {@code max-stops} 같은 룰이 더 잘라야 한다고 말하면 {@code GreedyAssigner} 가
 * 반으로 쪼개 다시 시도한다(§6.5 3단계) — 클러스터러가 룰의 속을 들여다보지 않아도 되는 이유다.
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
        int targetClusters = targetClusters(stops, capacity, vehicleCount);
        int targetSize = Math.max(1, Math.ceilDiv(stops.size(), targetClusters));

        List<Stop> swept = sweep(stops, depot.point());
        List<List<Stop>> clusters = new ArrayList<>();
        List<Stop> current = new ArrayList<>();
        Parcel load = Parcel.EMPTY;
        String zone = null;

        for (Stop stop : swept) {
            Parcel next = load.plus(stop.parcel());
            boolean overCapacity = !capacity.admits(next);
            // 목표 개수의 마지막 하나를 만들기 시작하면 더 자르지 않는다. 크기·경계로 계속
            // 자르면 클러스터가 목표(=차량 수 이하)를 넘고, 남는 것이 이미 실은 차에 얹혀
            // 지그재그가 된다 — 용량 초과만은 예외다(어떤 차도 실을 수 없는 클러스터가 된다).
            boolean mayCut = clusters.size() < targetClusters - 1;
            boolean overTarget = mayCut && current.size() >= targetSize;
            boolean zoneChanged = mayCut && zone != null && !zone.equals(stop.zone())
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
     * 필요한 클러스터 수. <strong>총수요 ÷ 가장 큰 차량의 용량</strong>이다 — 중량과 부피 중
     * 더 많이 요구하는 쪽을 쓴다. 차량 수를 상한으로 두는 이유는 클러스터가 차보다 많으면 남는
     * 것이 이미 실은 차에 얹혀 부챗살 여럿을 오가는 지그재그가 되기 때문이다.
     */
    private static int targetClusters(List<Stop> stops, Capacity capacity, int vehicleCount) {
        long weight = stops.stream().mapToLong(stop -> stop.parcel().weightG()).sum();
        long volume = stops.stream().mapToLong(stop -> stop.parcel().volumeCm3()).sum();
        long byWeight = Math.ceilDiv(weight, Math.max(1L, capacity.maxWeightG()));
        long byVolume = Math.ceilDiv(volume, Math.max(1L, capacity.maxVolumeCm3()));
        return (int) Math.min(vehicleCount, Math.max(1L, Math.max(byWeight, byVolume)));
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
