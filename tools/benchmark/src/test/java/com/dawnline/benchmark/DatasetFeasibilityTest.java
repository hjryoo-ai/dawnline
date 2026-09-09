package com.dawnline.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.dispatch.domain.optimizer.Candidate;
import com.dawnline.dispatch.domain.optimizer.Capacity;
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
 *   <li><strong>모든 제약 조합</strong>(냉장 × 위험물 × 대형)에 대해, 그 능력을 <em>최소한</em>
 *       요구하는 수요가 그 능력을 <em>모두</em> 갖춘 차량 용량의 <strong>80% 이하</strong>.
 *       위의 두 기준은 축을 <em>따로</em> 본다 — 총량과 냉장. 그런데 배차가 실제로 막히는 자리는
 *       <strong>축이 겹치는 곳</strong>이고, 겹친 수요는 겹친 능력을 가진 차량만 실을 수 있다.
 *       §6.7 의 "미배정률 ≤ 0.5%(정상 용량)" 을 조합 단위로 옮긴 문장이다.</li>
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

    /**
     * 제약 조합별 여유. 총량 기준(70%)보다 느슨한 이유는 <strong>목적이 다르기</strong> 때문이다 —
     * 여기서 보는 것은 "여유로운가" 가 아니라 <strong>"막다른 길이 아닌가"</strong> 다. 조합별
     * 차량 수는 작아서 한 대가 늘고 주는 것이 비율을 크게 흔든다.
     */
    private static final double CLASS_HEADROOM = 0.80d;

    private static final boolean[] BOTH = {false, true};

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

    /**
     * <h2>왜 통합 <em>후</em> 의 stop 으로 재는가</h2>
     * 계획이 실제로 배치하는 단위가 stop 이고, §6.5 1단계의 통합은 <strong>제약을 전파한다</strong> —
     * 같은 건물·같은 창의 주문 열 건 중 하나가 위험물이면 stop 전체가 위험물이 되어 열 건이 함께
     * 위험물 차량을 기다린다. 원본 후보로 재면 그 전파가 보이지 않고, 보이지 않는 수요는
     * "정상 용량" 판정에서 빠진다.
     *
     * <p>그래서 이 기준은 통합 키(§6.5 1단계)가 바뀌면 함께 움직인다. 그건 결함이 아니라 이
     * 기준이 <em>모델을 포함해</em> 실현 가능성을 묻는다는 뜻이다.
     */
    @ParameterizedTest
    @EnumSource(value = Dataset.class, names = {"SMALL", "MEDIUM", "LARGE"})
    void 모든_제약_조합에서_수요가_그_조합의_차량_용량의_80퍼센트를_넘지_않는다(Dataset dataset) {
        PlanningProblem problem = problem(dataset);
        List<Stop> stops = StopMerger.merge(problem.candidates());
        Capacity smallest = smallestCapacity(problem.vehicles());
        List<String> violations = new java.util.ArrayList<>();

        // 조합을 손으로 나열하지 않는다 — 모델의 축에서 뽑는다. 축이 하나 늘면 기준이 따라온다.
        for (boolean cold : BOTH) {
            for (boolean hazmat : BOTH) {
                for (boolean large : BOTH) {
                    check(dataset, stops, problem.vehicles(), smallest, cold, hazmat, large,
                            violations);
                }
            }
        }

        assertThat(violations)
                .as("겹친 제약이 막다른 길이 되면 어떤 알고리즘도 그 수요를 실을 수 없다 — "
                        + "그 표는 라우팅 품질이 아니라 용량 부족을 잰다")
                .isEmpty();
    }

    /** 이 능력을 <em>최소한</em> 요구하는 수요와, 그 능력을 <em>모두</em> 갖춘 차량을 맞대 본다. */
    private static void check(Dataset dataset, List<Stop> stops, List<VehicleSpec> vehicles,
            Capacity smallest, boolean cold, boolean hazmat, boolean large,
            List<String> violations) {

        List<Stop> demand = stops.stream()
                .filter(stop -> !cold || stop.parcel().requiresCold())
                .filter(stop -> !hazmat || stop.parcel().hazmat())
                .filter(stop -> !large || !fits(smallest, stop))
                .toList();
        if (demand.isEmpty()) {
            return;                             // 이 조합의 수요가 없으면 잴 것이 없다
        }
        List<VehicleSpec> fleet = vehicles.stream()
                .filter(vehicle -> !cold || vehicle.attrs().cold())
                .filter(vehicle -> !hazmat || vehicle.attrs().allowsHazmat())
                .filter(vehicle -> !large || exceeds(vehicle.capacity(), smallest))
                .toList();

        String label = "%s %s".formatted(dataset.cliName(), describe(cold, hazmat, large));
        if (fleet.isEmpty()) {
            violations.add("%s — 수요 %d stop 인데 실을 수 있는 차량이 0 대다 (막다른 길)"
                    .formatted(label, demand.size()));
            return;
        }

        long weight = demand.stream().mapToLong(stop -> stop.parcel().weightG()).sum();
        long volume = demand.stream().mapToLong(stop -> stop.parcel().volumeCm3()).sum();
        long capacityWeight = fleet.stream().mapToLong(v -> v.capacity().maxWeightG()).sum();
        long capacityVolume = fleet.stream().mapToLong(v -> v.capacity().maxVolumeCm3()).sum();

        if ((double) weight / capacityWeight > CLASS_HEADROOM) {
            violations.add("%s — 중량 %,d / %,d g = %.0f%% (차량 %d대, stop %d개)".formatted(
                    label, weight, capacityWeight, 100.0d * weight / capacityWeight,
                    fleet.size(), demand.size()));
        }
        if ((double) volume / capacityVolume > CLASS_HEADROOM) {
            violations.add("%s — 부피 %,d / %,d cm3 = %.0f%% (차량 %d대, stop %d개)".formatted(
                    label, volume, capacityVolume, 100.0d * volume / capacityVolume,
                    fleet.size(), demand.size()));
        }
    }

    private static String describe(boolean cold, boolean hazmat, boolean large) {
        List<String> parts = new java.util.ArrayList<>();
        if (cold) {
            parts.add("냉장");
        }
        if (hazmat) {
            parts.add("위험물");
        }
        if (large) {
            parts.add("대형");
        }
        return parts.isEmpty() ? "[제약 없음]" : String.join("∧", parts);
    }

    /** 가장 작은 차량의 용량. "대형" 은 여기 안 들어가는 stop 이다. */
    private static Capacity smallestCapacity(List<VehicleSpec> vehicles) {
        return vehicles.stream().map(VehicleSpec::capacity)
                .min(java.util.Comparator.comparingLong(
                        capacity -> (long) capacity.maxWeightG() + capacity.maxVolumeCm3()))
                .orElseThrow();
    }

    private static boolean fits(Capacity capacity, Stop stop) {
        return stop.parcel().weightG() <= capacity.maxWeightG()
                && stop.parcel().volumeCm3() <= capacity.maxVolumeCm3();
    }

    private static boolean exceeds(Capacity capacity, Capacity smallest) {
        return capacity.maxWeightG() > smallest.maxWeightG()
                || capacity.maxVolumeCm3() > smallest.maxVolumeCm3();
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
