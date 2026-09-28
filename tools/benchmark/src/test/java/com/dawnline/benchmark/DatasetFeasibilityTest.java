package com.dawnline.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.TierSchedule;
import com.dawnline.common.TimeWindow;
import com.dawnline.common.fleet.FleetFeasibility;
import com.dawnline.common.fleet.FleetFeasibility.Assessment;
import com.dawnline.common.fleet.FleetFeasibility.Line;
import com.dawnline.dispatch.domain.PlanMode;
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
import java.util.stream.Collectors;
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
 *   <li><strong>웨이브 하나에 약속창은 하나</strong>이고 그 창은 웨이브의 (티어, 컷오프)에서 {@link TierSchedule#windowFor}
 *       가 내는 창이다(§2.2 — 웨이브는 (캠프, 티어, 컷오프)이고 창은 컷오프에서 유도된다). 처음 판의 창 셋은 어느 실제 웨이브와도
 *       맞지 않았고, 기다림을 넣자 그 레짐에서 비용이 두 배가 됐다
 *       ([ADR-075](../../../../../docs/adr/ADR-075-promise-start-is-a-floor-vehicle-time-belongs-to-the-earlier-plan.md) 결정 1 —
 *       이 기준은 수치를 보기 전에 적었다).</li>
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

    // 제약 조합별 여유는 FleetFeasibility.HEADROOM_PERCENT(80)다. 총량 기준(70%)보다 느슨한 이유는 <목적이 다르기> 때문이다 —
    // 여기서 보는 것은 "여유로운가" 가 아니라 "막다른 길이 아닌가" 다. 조합별 차량 수는 작아서 한 대가 늘고 주는 것이
    // 비율을 크게 흔든다. 그 값은 이제 성수기 증차의 대수도 정한다(ADR-067) — 여기서 따로 적지 않는다.

    /**
     * stop 수 축의 여유 — <strong>조합 기준과 같은 80%</strong>다 (2026-09-12).
     *
     * <p>원래 이 축만 여유가 0% 였다(「{@code 차량 수 × 120} 이하」). 그건 <em>완벽한 패킹</em>을
     * 요구하는 수인데, 같은 이유로 중량·부피에는 이미 여유를 두고 있었다 — 축 하나만 기준이
     * 달랐던 것이다. [ADR-033](../../../../../docs/adr/ADR-033-constraint-classes.md) 의 80%를
     * stop 축으로 옮긴다.
     */
    private static final double STOP_HEADROOM = FleetFeasibility.HEADROOM_PERCENT / 100.0d;

    // 실현 가능성 기준은 OVERLOAD 를 <strong>빼는 방식</strong>으로 적는다(EXCLUDE), 드는
    // 방식이 아니라 — 데이터셋이 새로 생기면 자동으로 검사 대상이 되어야 한다. 드는 방식이던
    // 2026-09-12 까지 `peak` 이 목록에 없었고, 그래서 stop 8,411 개가 슬롯 7,200 개를 넘는다는
    // 사실을 아무도 보지 못했다. OVERLOAD 의 «일부러 어긴다» 는
    // overload_는_stop_기준을_일부러_어긴다() 가 따로 말한다.

    /** 아무 능력도 요구하지 않는 조합 — 그 stop 축이 전체 stop 수 대 전체 슬롯이다. */
    private static final FleetFeasibility.Combination GENERAL = new FleetFeasibility.Combination(false, false, false);

    private static PlanningProblem problem(Dataset dataset) {
        return new DatasetGenerator(dataset, 20_260_905L, START).generate(RuleSet.empty(), BUDGET, PlanMode.FULL, 1.0d);
    }

    @ParameterizedTest
    @EnumSource(value = Dataset.class, mode = EnumSource.Mode.EXCLUDE, names = "OVERLOAD")
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
    @EnumSource(value = Dataset.class, mode = EnumSource.Mode.EXCLUDE, names = "OVERLOAD")
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
     *
     * <h2>계산은 {@code libs/common} 의 것이다</h2>
     * 2026-09-27(ADR-067)부터 조합 판정은 {@link FleetFeasibility} 가 한다 — dispatch 의 {@code fleet-feasibility} 가 성수기
     * 증차의 대수를 내는 <strong>같은 코드</strong>다. 벤치마크의 기준과 시뮬레이터의 증차가 갈라지면 갈라진 쪽은 조용하다.
     * 옮기며 조합마다 stop 축(상한 120 의 80%)이 함께 들어왔다 — 일반 조합의 stop 축이 아래 「통합 후 stop 수」다.
     */
    @ParameterizedTest
    @EnumSource(value = Dataset.class, mode = EnumSource.Mode.EXCLUDE, names = "OVERLOAD")
    void 모든_제약_조합에서_수요가_그_조합의_차량_용량의_80퍼센트를_넘지_않는다(Dataset dataset) {
        Assessment assessment = assess(problem(dataset));

        assertThat(assessment.violations().stream().map(line -> describe(dataset, line)).toList())
                .as("겹친 제약이 막다른 길이 되면 어떤 알고리즘도 그 수요를 실을 수 없다 — "
                        + "그 표는 라우팅 품질이 아니라 용량 부족을 잰다")
                .isEmpty();
    }

    /** 통합 후 stop 과 차량을 {@link FleetFeasibility} 의 모양으로 옮겨 잰다. 상한은 시드 룰의 {@code max-stops}. */
    private static Assessment assess(PlanningProblem problem) {
        List<FleetFeasibility.Stop> stops = StopMerger.merge(problem.candidates()).stream()
                .map(stop -> new FleetFeasibility.Stop(stop.parcel().requiresCold(), stop.parcel().hazmat(),
                        stop.parcel().weightG(), stop.parcel().volumeCm3()))
                .toList();
        List<FleetFeasibility.Vehicle> fleet = problem.vehicles().stream()
                .map(vehicle -> new FleetFeasibility.Vehicle(vehicle.id().value().toString(),
                        vehicle.id().value().toString(), vehicle.attrs().cold(), vehicle.attrs().allowsHazmat(),
                        vehicle.capacity().maxWeightG(), vehicle.capacity().maxVolumeCm3(),
                        vehicle.cost().fixed().krw()))
                .toList();
        return FleetFeasibility.assess(stops, fleet, MAX_STOPS);
    }

    private static String describe(Dataset dataset, Line line) {
        return "%s %s — %s: stop %,d / 슬롯 %,d · 중량 %,d / %,d g · 부피 %,d / %,d cm3 (차량 %d대, 부족 %s대)".formatted(
                dataset.cliName(), line.combination().label(), line.status(),
                line.demand().stops(), line.capacity().stops(), line.demand().weightG(), line.capacity().weightG(),
                line.demand().volumeCm3(), line.capacity().volumeCm3(), line.vehicles(), line.shortfall());
    }

    @ParameterizedTest
    @EnumSource(value = Dataset.class, mode = EnumSource.Mode.EXCLUDE, names = "OVERLOAD")
    void 통합_후_stop_수가_차량_stop_슬롯의_80퍼센트를_넘지_않는다(Dataset dataset) {
        PlanningProblem problem = problem(dataset);
        Line general = assess(problem).line(GENERAL).orElseThrow();

        assertThat((double) general.demand().stops() / general.capacity().stops())
                .as("%s stop %,d / 슬롯 %,d (차량 %d × %d) — 넘으면 MAX_STOPS_PER_ROUTE 만으로 "
                                + "미배정이 확정된다",
                        dataset.cliName(), general.demand().stops(), general.capacity().stops(),
                        problem.vehicles().size(), MAX_STOPS)
                .isLessThanOrEqualTo(STOP_HEADROOM);
    }

    @ParameterizedTest
    @EnumSource(value = Dataset.class, mode = EnumSource.Mode.EXCLUDE, names = "OVERLOAD")
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

    @ParameterizedTest
    @EnumSource(Dataset.class)
    void 웨이브의_모든_후보는_컷오프에서_유도한_약속창_하나를_갖는다(Dataset dataset) {
        PlanningProblem problem = problem(dataset);
        TimeWindow expected = TierSchedule.standard().windowFor(problem.wave().serviceTier(), problem.wave().cutoffAt());

        assertThat(problem.candidates().stream().map(Candidate::promised).collect(Collectors.toSet()))
                .as("%s — 웨이브는 (캠프, 티어, 컷오프)이고 창은 컷오프에서 유도된다(§2.2). 창이 여럿인 웨이브는 "
                        + "어느 티어에도 없는 레짐이다", dataset.cliName())
                .containsExactly(expected);
    }

    /**
     * {@code overload} 는 <strong>기준을 어기는 것이 목적</strong>이다 — 그 사실을 테스트가
     * 스스로 말한다.
     *
     * <p>말하지 않으면 두 가지가 조용히 일어난다. ① 다음 사람이 이것을 결함으로 보고 «고친다»
     * (차량을 늘린다) — 그러면 과부하 거동을 재는 자리가 사라진다. ② 반대로 누군가 이것을
     * 정상 데이터셋으로 읽고 §6.9 비교표에 같은 절로 싣는다 — 그러면 표가 재는 것이 라우팅
     * 품질이 아니라 용량이 된다.
     */
    @org.junit.jupiter.api.Test
    void overload_는_stop_기준을_일부러_어긴다() {
        Line general = assess(problem(Dataset.OVERLOAD)).line(GENERAL).orElseThrow();

        assertThat((double) general.demand().stops() / general.capacity().stops())
                .as("overload stop %,d / 슬롯 %,d — 이 데이터셋의 존재 이유가 «다 못 싣는다» 다. "
                                + "이 어설션이 깨졌다면 차량을 늘린 것이고, 그건 도구를 없앤 것이다",
                        general.demand().stops(), general.capacity().stops())
                .isGreaterThan(STOP_HEADROOM);
        assertThat(general.status()).as("같은 판정을 증차의 계산이 «부족» 으로 읽는다")
                .isNotEqualTo(FleetFeasibility.Status.FEASIBLE);
        assertThat(problem(Dataset.OVERLOAD).candidates()).as("peak 과 같은 주문 수 — 차이는 대수뿐이다")
                .hasSameSizeAs(problem(Dataset.PEAK).candidates());
    }

    private static long sum(List<Candidate> candidates, java.util.function.ToLongFunction<Candidate> field) {
        return candidates.stream().mapToLong(field).sum();
    }

    private static long sumVehicles(List<VehicleSpec> vehicles,
            java.util.function.ToLongFunction<VehicleSpec> field) {
        return vehicles.stream().mapToLong(field).sum();
    }
}
