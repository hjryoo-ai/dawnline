package com.dawnline.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.dispatch.domain.optimizer.Candidate;
import com.dawnline.dispatch.domain.optimizer.PlanningBudget;
import com.dawnline.dispatch.domain.optimizer.PlanningProblem;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.StopMerger;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 데이터셋이 <strong>실현 가능한가</strong> — 측정 전에 정해 두는 기준.
 *
 * <h2>왜 이 테스트가 먼저인가</h2>
 * 수요가 용량을 넘으면 어떤 알고리즘도 미배정을 없앨 수 없다. 그런 데이터셋에서 나온 표는
 * 라우팅 품질이 아니라 <strong>용량 부족</strong>을 재는 것이고, 두 전략의 차이는 그 잡음에 묻힌다.
 * §6.7 의 목표 "미배정률 ≤ 0.5% (정상 용량)" 에서 <em>정상 용량</em>이 무슨 뜻인지를 여기서 정한다.
 *
 * <p>기준을 <strong>수치를 보기 전에</strong> 적는 이유는 Phase 1 의 원인 판정표와 같다 — 결과를
 * 본 뒤에 기준을 만들면 어떤 데이터셋이든 "적절하다" 가 된다.
 *
 * <h2>기준</h2>
 * <ul>
 *   <li>총 중량·부피가 전체 차량 용량의 <strong>70% 이하</strong>. 100% 는 완벽한 패킹을 요구하고,
 *       그건 알고리즘이 아니라 운이다.</li>
 *   <li>냉장 수요가 냉장 차량 용량의 <strong>70% 이하</strong>. 하드 룰이 실제로 걸리되 막다른
 *       길은 아니어야 한다.</li>
 *   <li>통합 후 stop 수가 <strong>{@code 차량 수 × max-stops(120)} 이하</strong>.
 *       그렇지 않으면 {@code MAX_STOPS_PER_ROUTE} 만으로 미배정이 확정된다.</li>
 *   <li><strong>유효</strong> stop 슬롯이 stop 수의 <strong>1.2배 이상</strong>. 차량의 stop 상한과
 *       적재 용량 중 <em>먼저 걸리는 쪽</em>이 그 차의 실제 슬롯이다 — 30 kg 자전거는 상한이 120
 *       이어도 평균 화물로 10 곳밖에 못 간다. 이 기준이 빠져 있어 첫 측정에서 미배정 83건 중 71건이
 *       {@code max-stops} 였다. 알고리즘이 아니라 <em>차가 모자란 것</em>이었다.</li>
 * </ul>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DatasetFeasibilityTest {

    private static final Instant START = Instant.parse("2026-09-06T01:00:00Z");
    private static final PlanningBudget BUDGET =
            new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(3));
    /** 시드 룰의 {@code max-stops}. */
    private static final int MAX_STOPS = 120;
    private static final double HEADROOM = 0.70d;

    private static PlanningProblem problem(Dataset dataset) {
        return new DatasetGenerator(dataset, 20_260_905L, START).generate(RuleSet.empty(), BUDGET);
    }

    @ParameterizedTest
    @EnumSource(value = Dataset.class, names = {"SMALL", "MEDIUM", "LARGE"})
    void 총_수요가_차량_용량의_70퍼센트를_넘지_않는다(Dataset dataset) {
        PlanningProblem problem = problem(dataset);

        long weight = sum(problem.candidates(), candidate -> candidate.parcel().weightG());
        long volume = sum(problem.candidates(), candidate -> candidate.parcel().volumeCm3());
        long capacityWeight = sumVehicles(problem.vehicles(), v -> v.capacity().maxWeightG());
        long capacityVolume = sumVehicles(problem.vehicles(), v -> v.capacity().maxVolumeCm3());

        assertThat((double) weight / capacityWeight)
                .as("%s 중량 %,d / %,d g", dataset.cliName(), weight, capacityWeight)
                .isLessThanOrEqualTo(HEADROOM);
        assertThat((double) volume / capacityVolume)
                .as("%s 부피 %,d / %,d cm3", dataset.cliName(), volume, capacityVolume)
                .isLessThanOrEqualTo(HEADROOM);
    }

    @ParameterizedTest
    @EnumSource(value = Dataset.class, names = {"SMALL", "MEDIUM", "LARGE"})
    void 냉장_수요가_냉장_차량_용량의_70퍼센트를_넘지_않는다(Dataset dataset) {
        PlanningProblem problem = problem(dataset);

        long coldWeight = problem.candidates().stream()
                .filter(candidate -> candidate.parcel().requiresCold())
                .mapToLong(candidate -> candidate.parcel().weightG()).sum();
        long coldCapacity = problem.vehicles().stream()
                .filter(vehicle -> vehicle.attrs().cold())
                .mapToLong(vehicle -> vehicle.capacity().maxWeightG()).sum();

        assertThat(coldCapacity).as("냉장 차량이 있어야 cold-chain 룰이 막다른 길이 아니다")
                .isPositive();
        assertThat((double) coldWeight / coldCapacity)
                .as("%s 냉장 중량 %,d / %,d g", dataset.cliName(), coldWeight, coldCapacity)
                .isLessThanOrEqualTo(HEADROOM);
    }

    @ParameterizedTest
    @EnumSource(value = Dataset.class, names = {"SMALL", "MEDIUM", "LARGE"})
    void 통합_후_stop_수가_차량_stop_상한_안에_들어간다(Dataset dataset) {
        PlanningProblem problem = problem(dataset);
        List<Stop> stops = StopMerger.merge(problem.candidates());
        int slots = problem.vehicles().size() * MAX_STOPS;

        assertThat(stops.size())
                .as("%s stop %d, 슬롯 %d (차량 %d × %d) — 넘으면 MAX_STOPS_PER_ROUTE 만으로 미배정이 확정된다",
                        dataset.cliName(), stops.size(), slots, problem.vehicles().size(), MAX_STOPS)
                .isLessThanOrEqualTo(slots);
    }

    @ParameterizedTest
    @EnumSource(value = Dataset.class, names = {"SMALL", "MEDIUM", "LARGE"})
    void 유효_stop_슬롯이_stop_수의_1_2배_이상이다(Dataset dataset) {
        PlanningProblem problem = problem(dataset);
        List<Stop> stops = StopMerger.merge(problem.candidates());
        long averageWeight = Math.max(1L,
                sum(problem.candidates(), candidate -> candidate.parcel().weightG())
                        / problem.candidates().size());

        long effective = problem.vehicles().stream()
                .mapToLong(vehicle -> Math.min(MAX_STOPS, vehicle.capacity().maxWeightG() / averageWeight))
                .sum();

        assertThat((double) effective / stops.size())
                .as("%s 유효 슬롯 %d / stop %d (평균 화물 %,d g) — 1.2 미만이면 미배정이 알고리즘이 "
                                + "아니라 차량 부족에서 나온다",
                        dataset.cliName(), effective, stops.size(), averageWeight)
                .isGreaterThanOrEqualTo(1.2d);
    }

    private static long sum(List<Candidate> candidates, java.util.function.ToLongFunction<Candidate> field) {
        return candidates.stream().mapToLong(field).sum();
    }

    private static long sumVehicles(List<VehicleSpec> vehicles,
            java.util.function.ToLongFunction<VehicleSpec> field) {
        return vehicles.stream().mapToLong(field).sum();
    }
}
