package com.dawnline.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Haversine;
import com.dawnline.dispatch.domain.optimizer.Candidate;
import com.dawnline.dispatch.domain.optimizer.PlanningBudget;
import com.dawnline.dispatch.domain.optimizer.PlanningProblem;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DatasetGeneratorTest {

    private static final Instant START = Instant.parse("2026-09-06T01:00:00Z");
    private static final PlanningBudget BUDGET =
            new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(3));
    private static final GeoPoint CAMP = GeoPoint.of(37.5663, 126.9779);

    private PlanningProblem generate(Dataset dataset, long seed) {
        return new DatasetGenerator(dataset, seed, START).generate(RuleSet.empty(), BUDGET);
    }

    @Test
    void 규모가_설계서와_같다() {
        PlanningProblem small = generate(Dataset.SMALL, 1L);

        assertThat(small.candidates()).hasSize(500);
        assertThat(small.vehicles()).hasSize(5);
    }

    @Test
    void 같은_seed_는_같은_문제를_만든다() {
        // 벤치마크는 몇 달 뒤 같은 표를 다시 내야 한다 (불변규칙 12).
        List<GeoPoint> first = generate(Dataset.SMALL, 42L).candidates().stream()
                .map(Candidate::point).toList();
        List<GeoPoint> second = generate(Dataset.SMALL, 42L).candidates().stream()
                .map(Candidate::point).toList();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void 다른_seed_는_다른_문제를_만든다() {
        List<GeoPoint> first = generate(Dataset.SMALL, 1L).candidates().stream()
                .map(Candidate::point).toList();
        List<GeoPoint> second = generate(Dataset.SMALL, 2L).candidates().stream()
                .map(Candidate::point).toList();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void 모든_배송지가_캠프_반경_8km_안에_있다() {
        // 군집 주변 가우시안이 반경을 넘을 수 있어 clamp 한다. 넘으면 거리 행렬이 설계 가정을 깬다.
        assertThat(generate(Dataset.SMALL, 7L).candidates())
                .allSatisfy(candidate -> assertThat(Haversine.meters(CAMP, candidate.point()))
                        .isLessThanOrEqualTo(8_001.0d));
    }

    @Test
    void 밀도가_균일하지_않다() {
        // 균일 분포면 어떤 알고리즘이든 비슷한 답을 내서 전략 차이가 드러나지 않는다.
        // 권역(geohash5)별 주문 수의 최댓값이 평균의 두 배를 넘는지로 본다.
        var byZone = generate(Dataset.MEDIUM, 3L).candidates().stream()
                .collect(java.util.stream.Collectors.groupingBy(Candidate::zone,
                        java.util.stream.Collectors.counting()));
        double average = byZone.values().stream().mapToLong(Long::longValue).average().orElseThrow();
        long max = byZone.values().stream().mapToLong(Long::longValue).max().orElseThrow();

        assertThat(max).as("가장 붐비는 권역이 평균의 2배는 넘어야 뭉쳐 있다고 할 수 있다")
                .isGreaterThan((long) (average * 2));
    }

    @Test
    void 냉장_주문과_냉장_차량이_함께_있다() {
        // 전부 냉장차면 cold-chain 하드 룰이 한 번도 걸리지 않아 아무것도 검증하지 못한다.
        PlanningProblem problem = generate(Dataset.SMALL, 11L);
        long coldOrders = problem.candidates().stream()
                .filter(candidate -> candidate.parcel().requiresCold()).count();
        long coldVehicles = problem.vehicles().stream()
                .filter(vehicle -> vehicle.attrs().cold()).count();

        assertThat(coldOrders).isBetween(100L, 150L);
        assertThat(coldVehicles).isBetween(1L, (long) problem.vehicles().size() - 1);
    }

    @Test
    void 차종이_섞여_있다() {
        // 자전거는 넣지 않는다 — 이 규모(차량당 약 100 stop)에서 30 kg 은 산술적으로 맞지 않는다.
        // 근거와 검사는 DatasetFeasibilityTest 에 있다.
        Set<String> types = generate(Dataset.MEDIUM, 5L).vehicles().stream()
                .map(vehicle -> vehicle.attrs().type()).collect(java.util.stream.Collectors.toSet());

        assertThat(types).containsExactlyInAnyOrder("VAN", "TRUCK");
    }

    @Test
    void 선호하지_않는_차종이_섞여_있다() {
        // 시드가 선호하는 것은 BIKE·VAN 이므로 TRUCK 이 VEHICLE_PREFERENCE 페널티를 받는다.
        // 전부 선호 차종이면 그 소프트 룰이 한 번도 돌지 않는다.
        long trucks = generate(Dataset.MEDIUM, 5L).vehicles().stream()
                .filter(vehicle -> "TRUCK".equals(vehicle.attrs().type())).count();

        assertThat(trucks).isPositive();
    }

    @Test
    void 우선_고객이_섞여_있다() {
        long vip = generate(Dataset.SMALL, 13L).candidates().stream()
                .filter(candidate -> candidate.priority() > 0).count();

        assertThat(vip).as("PRIORITY_BOOST 가 실제로 돌아야 한다").isBetween(30L, 80L);
    }

    @Test
    void 계획_시작_시각과_seed_가_문제에_들어_있다() {
        // 순수 함수는 시각도 난수도 입력으로 받는다 (불변규칙 12).
        PlanningProblem problem = generate(Dataset.SMALL, 99L);

        assertThat(problem.startedAt()).isEqualTo(START);
        assertThat(problem.seed()).isEqualTo(99L);
    }
}
