package com.dawnline.benchmark;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.domain.optimizer.CampDepot;
import com.dawnline.dispatch.domain.optimizer.Candidate;
import com.dawnline.dispatch.domain.optimizer.Capacity;
import com.dawnline.dispatch.domain.optimizer.CostModel;
import com.dawnline.dispatch.domain.optimizer.HaversineDistance;
import com.dawnline.dispatch.domain.optimizer.OrderId;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.PlanningBudget;
import com.dawnline.dispatch.domain.optimizer.PlanningProblem;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import com.dawnline.dispatch.domain.optimizer.VehicleAttrs;
import com.dawnline.dispatch.domain.optimizer.VehicleCost;
import com.dawnline.dispatch.domain.optimizer.VehicleId;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import com.dawnline.dispatch.domain.optimizer.WaveRef;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * seed 로 고정된 문제 생성기 (DESIGN.md §6.9).
 *
 * <h2>왜 {@link Random} 인가</h2>
 * 불변규칙 12 는 {@code RandomGenerator} 를 주입하라고 하고, 그중 {@link Random} 은 <strong>같은
 * seed 에 같은 수열을 낸다는 것이 JDK 명세로 보장된</strong> 유일한 구현이다. 벤치마크는 몇 달 뒤
 * 다른 JDK 버전에서 같은 표를 다시 내야 하므로 그 보장이 필요하다.
 *
 * <h2>좌표 분포</h2>
 * 캠프 중심 반경 8 km, <strong>밀도 불균일</strong>(§6.9). 균일 분포로 만들면 어떤 알고리즘이든
 * 비슷한 답을 내서 전략 차이가 드러나지 않는다 — 실제 배송지는 뭉쳐 있고, 뭉친 곳을 어떻게 묶느냐가
 * 이 문제의 본질이다.
 *
 * <p>세 겹이다. <strong>35%는 집합 건물</strong>(고정된 200개 지점) — 같은 주소로 여러 건이 가는
 * 아파트·오피스가 현실의 상당수이고, {@link com.dawnline.dispatch.domain.optimizer.StopMerger} 가
 * 하는 일이 정확히 그것이다. 나머지 중 다수는 8개 군집 주변, 일부는 전역에 흩는다.
 *
 * <h2>약속창은 세 개뿐이다</h2>
 * 주문마다 다른 창을 주면 통합 조건("같은 geohash7 + 같은 약속창")이 사실상 성립하지 않아
 * {@code StopMerger} 가 아무 일도 못 한다 — 첫 측정에서 통합률이 0.08% 였다. §2.2 의 실제 티어는
 * 웨이브 하나에 창이 몇 개뿐이므로, 무작위 창이 오히려 비현실적이었다.
 *
 * <h2>규모는 실현 가능해야 한다</h2>
 * 수요가 용량을 넘으면 어떤 알고리즘도 미배정을 없앨 수 없고, 그 표는 라우팅 품질이 아니라 용량
 * 부족을 잰다. 기준과 검사는 {@code DatasetFeasibilityTest} 에 있다 — 수치를 보기 전에 적었다.
 */
public final class DatasetGenerator {

    /** 서울시청 근방 — 캠프 중심. */
    private static final GeoPoint CAMP = GeoPoint.of(37.5663, 126.9779);

    private static final double RADIUS_M = 8_000.0d;
    private static final int CLUSTERS = 8;
    /** 같은 주소로 여러 건이 가는 집합 건물 수. */
    private static final int BUILDINGS = 200;
    private static final double BUILDING_RATIO = 0.35d;
    private static final double CLUSTERED_RATIO = 0.85d;
    /** 군집 하나의 퍼짐(m). */
    private static final double CLUSTER_SIGMA_M = 700.0d;
    /** 웨이브 하나의 약속창 수 (§2.2 — 티어당 창이 몇 개뿐이다). */
    private static final int PROMISED_WINDOWS = 3;

    /** 위도 1도 ≈ 111.32 km. 8 km 반경에서는 평면 근사로 충분하다. */
    private static final double METERS_PER_DEGREE_LAT = 111_320.0d;

    private final Dataset dataset;
    private final long seed;
    private final Instant startedAt;

    /**
     * @param dataset   규모
     * @param seed      난수 seed. 같으면 같은 문제가 나온다
     * @param startedAt 계획 시작 시각. 약속창·근무창의 기준점이다
     */
    public DatasetGenerator(Dataset dataset, long seed, Instant startedAt) {
        this.dataset = Objects.requireNonNull(dataset, "dataset");
        this.seed = seed;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    }

    /**
     * 문제를 만든다.
     *
     * @param rules  적용할 룰 묶음
     * @param budget 시간 예산
     */
    public PlanningProblem generate(RuleSet rules, PlanningBudget budget) {
        RandomGenerator random = new Random(seed);
        UUID campId = Ids.newId();
        CampDepot depot = new CampDepot(campId, CAMP);
        List<GeoPoint> clusters = clusterCenters(random);
        List<GeoPoint> buildings = buildingPoints(random, clusters);
        List<TimeWindow> windows = promisedWindows();

        return new PlanningProblem(
                new WaveRef(Ids.newId(), campId, "SAME_DAY", startedAt),
                depot,
                candidates(random, clusters, buildings, windows),
                vehicles(random),
                rules,
                new CostModel(),
                // 도로계수 1.3, 평균 25 km/h — §6.2 의 기본값
                new HaversineDistance(1.3d, 25.0d),
                budget,
                startedAt,
                seed);
    }

    private List<GeoPoint> clusterCenters(RandomGenerator random) {
        List<GeoPoint> centers = new ArrayList<>(CLUSTERS);
        for (int i = 0; i < CLUSTERS; i++) {
            double angle = random.nextDouble() * 2 * Math.PI;
            // sqrt 를 취해야 원판 위에서 면적당 균일해진다. 그냥 곱하면 중심에 몰린다.
            double distance = Math.sqrt(random.nextDouble()) * RADIUS_M;
            centers.add(offset(CAMP, Math.cos(angle) * distance, Math.sin(angle) * distance));
        }
        return List.copyOf(centers);
    }

    /** 웨이브 하나의 약속창들. 겹치지 않게 두 시간씩 밀어 시간 룰이 실제로 갈린다. */
    private List<TimeWindow> promisedWindows() {
        List<TimeWindow> windows = new ArrayList<>(PROMISED_WINDOWS);
        for (int i = 0; i < PROMISED_WINDOWS; i++) {
            Instant start = startedAt.plus(Duration.ofHours(2L + i * 2L));
            windows.add(new TimeWindow(start, start.plus(Duration.ofHours(4))));
        }
        return List.copyOf(windows);
    }

    /** 집합 건물 — 군집 안에 둔다. 실제로 아파트 단지는 흩어진 곳이 아니라 밀집지에 있다. */
    private List<GeoPoint> buildingPoints(RandomGenerator random, List<GeoPoint> clusters) {
        List<GeoPoint> buildings = new ArrayList<>(BUILDINGS);
        for (int i = 0; i < BUILDINGS; i++) {
            buildings.add(nearCluster(random, clusters.get(random.nextInt(clusters.size()))));
        }
        return List.copyOf(buildings);
    }

    private List<Candidate> candidates(RandomGenerator random, List<GeoPoint> clusters,
            List<GeoPoint> buildings, List<TimeWindow> windows) {

        List<Candidate> candidates = new ArrayList<>(dataset.orders());
        for (int i = 0; i < dataset.orders(); i++) {
            double where = random.nextDouble();
            GeoPoint point;
            if (where < BUILDING_RATIO) {
                point = buildings.get(random.nextInt(buildings.size()));
            } else if (where < CLUSTERED_RATIO) {
                point = nearCluster(random, clusters.get(random.nextInt(clusters.size())));
            } else {
                point = uniformInDisk(random);
            }

            TimeWindow promised = windows.get(random.nextInt(windows.size()));

            // 냉장 25% (order-service smoke 시나리오의 cold-ratio 와 같은 값), 위험물 2%.
            // 중량·부피의 상한은 DatasetFeasibilityTest 의 70% 여유 기준에서 나온 값이다.
            Parcel parcel = new Parcel(
                    500 + random.nextInt(4_600),
                    1_000 + random.nextInt(11_000),
                    random.nextDouble() < 0.25d,
                    random.nextDouble() < 0.02d);

            // 우선 고객 10%. PRIORITY_BOOST 가 실제로 돌게 하려는 비율이다.
            int priority = random.nextDouble() < 0.10d ? 1 + random.nextInt(2) : 0;

            candidates.add(new Candidate(OrderId.of(Ids.newId()), point, parcel, promised,
                    60 + random.nextInt(120), priority));
        }
        return List.copyOf(candidates);
    }

    private List<VehicleSpec> vehicles(RandomGenerator random) {
        List<VehicleSpec> vehicles = new ArrayList<>(dataset.vehicles());
        // 근무창은 전 차량 공통 10시간. 차량마다 흔들면 미배정의 원인이 근무창인지 용량인지
        // 구분되지 않아 전략 비교가 흐려진다.
        TimeWindow shift = new TimeWindow(startedAt, startedAt.plus(Duration.ofHours(10)));
        for (int i = 0; i < dataset.vehicles(); i++) {
            // 자전거를 넣지 않는다. 이 규모는 차량당 약 100 stop 을 요구하는데(§6.9 의 500/5 ·
            // 2000/20 · 5000/40), 30 kg 자전거는 평균 2.8 kg 화물로 10곳밖에 못 간다 — 선호가
            // 아니라 산술이다. 자전거는 다른 화물 프로파일(서류·음식)의 차량이고, 이 데이터셋에
            // 넣으면 미배정이 알고리즘이 아니라 차량 부족에서 나온다(DatasetFeasibilityTest).
            // VEHICLE_PREFERENCE 는 여전히 문다 — 시드가 선호하는 것은 BIKE·VAN 이라 TRUCK 이
            // 페널티를 받는다.
            String type = i % 2 == 0 ? "VAN" : "TRUCK";
            // 냉장은 대수가 아니라 **용량**으로 맞춘다. 차량 수의 40%를 냉장으로 두었더니 작은
            // 차만 냉장이 되어 냉장 용량이 수요의 66% 밖에 안 됐다(첫 측정: 650kg 수요 / 430kg 용량).
            // 트럭은 전부, 밴은 넷 중 하나만 냉장으로 둔다 — 냉장이 아닌 차가 충분히 남아야
            // cold-chain 하드 룰이 실제로 배정을 바꾼다.
            boolean cold = "TRUCK".equals(type) || i % 8 == 0;
            boolean hazmat = i % 10 == 0;
            Capacity capacity = "VAN".equals(type)
                    ? new Capacity(400_000, 1_200_000)
                    : new Capacity(1_200_000, 4_000_000);
            VehicleCost cost = "VAN".equals(type)
                    ? VehicleCost.krw(45_000, 600, 250)
                    : VehicleCost.krw(80_000, 1_100, 400);
            vehicles.add(new VehicleSpec(VehicleId.of(Ids.newId()), capacity,
                    new VehicleAttrs(type, cold, hazmat), shift, cost));
        }
        return List.copyOf(vehicles);
    }

    private GeoPoint nearCluster(RandomGenerator random, GeoPoint center) {
        double east = random.nextGaussian() * CLUSTER_SIGMA_M;
        double north = random.nextGaussian() * CLUSTER_SIGMA_M;
        return clamp(offset(center, east, north));
    }

    private GeoPoint uniformInDisk(RandomGenerator random) {
        double angle = random.nextDouble() * 2 * Math.PI;
        double distance = Math.sqrt(random.nextDouble()) * RADIUS_M;
        return offset(CAMP, Math.cos(angle) * distance, Math.sin(angle) * distance);
    }

    /** 군집 주변 가우시안은 반경을 넘을 수 있다. 넘으면 경계로 당긴다. */
    private GeoPoint clamp(GeoPoint point) {
        double straight = com.dawnline.common.Haversine.meters(CAMP, point);
        if (straight <= RADIUS_M) {
            return point;
        }
        double ratio = RADIUS_M / straight;
        return offset(CAMP,
                (point.lng() - CAMP.lng()) * metersPerDegreeLng() * ratio,
                (point.lat() - CAMP.lat()) * METERS_PER_DEGREE_LAT * ratio);
    }

    private GeoPoint offset(GeoPoint origin, double eastMeters, double northMeters) {
        return GeoPoint.of(origin.lat() + northMeters / METERS_PER_DEGREE_LAT,
                origin.lng() + eastMeters / metersPerDegreeLng());
    }

    private double metersPerDegreeLng() {
        return METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(CAMP.lat()));
    }
}
