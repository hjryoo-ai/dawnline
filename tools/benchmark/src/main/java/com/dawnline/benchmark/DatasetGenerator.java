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
 * 이 문제의 본질이다. 주문의 70%는 8개 군집 주변, 30%는 전역에 흩는다.
 */
public final class DatasetGenerator {

    /** 서울시청 근방 — 캠프 중심. */
    private static final GeoPoint CAMP = GeoPoint.of(37.5663, 126.9779);

    private static final double RADIUS_M = 8_000.0d;
    private static final int CLUSTERS = 8;
    private static final double CLUSTERED_RATIO = 0.7d;
    /** 군집 하나의 퍼짐(m). */
    private static final double CLUSTER_SIGMA_M = 700.0d;

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

        return new PlanningProblem(
                new WaveRef(Ids.newId(), campId, "SAME_DAY", startedAt),
                depot,
                candidates(random, clusters),
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

    private List<Candidate> candidates(RandomGenerator random, List<GeoPoint> clusters) {
        List<Candidate> candidates = new ArrayList<>(dataset.orders());
        for (int i = 0; i < dataset.orders(); i++) {
            GeoPoint point = random.nextDouble() < CLUSTERED_RATIO
                    ? nearCluster(random, clusters.get(random.nextInt(clusters.size())))
                    : uniformInDisk(random);

            // 약속창은 계획 시작 뒤 2~8시간 사이에서 4시간짜리. 서로 어긋나야 시간 룰이 돈다.
            Instant windowStart = startedAt.plus(Duration.ofMinutes(120 + random.nextInt(360)));
            TimeWindow promised = new TimeWindow(windowStart, windowStart.plus(Duration.ofHours(4)));

            // 냉장 25% (order-service smoke 시나리오의 cold-ratio 와 같은 값), 위험물 2%.
            Parcel parcel = new Parcel(
                    500 + random.nextInt(9_500),
                    1_000 + random.nextInt(19_000),
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
            // 냉장차 40% — 냉장 주문 25% 를 감당하되 남지는 않는 비율이라 cold-chain 룰이 실제로
            // 배정을 바꾼다. 전부 냉장차면 그 하드 룰이 한 번도 걸리지 않는다.
            boolean cold = i % 5 < 2;
            boolean hazmat = i % 10 == 0;
            String type = switch (i % 3) {
                case 0 -> "BIKE";
                case 1 -> "VAN";
                default -> "TRUCK";
            };
            Capacity capacity = switch (type) {
                case "BIKE" -> new Capacity(30_000, 80_000);
                case "VAN" -> new Capacity(400_000, 1_200_000);
                default -> new Capacity(1_200_000, 4_000_000);
            };
            VehicleCost cost = switch (type) {
                case "BIKE" -> VehicleCost.krw(20_000, 300, 150);
                case "VAN" -> VehicleCost.krw(45_000, 600, 250);
                default -> VehicleCost.krw(80_000, 1_100, 400);
            };
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
