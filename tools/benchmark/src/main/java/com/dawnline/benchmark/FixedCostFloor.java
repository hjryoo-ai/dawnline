package com.dawnline.benchmark;

import com.dawnline.dispatch.domain.optimizer.ConstraintClass;
import com.dawnline.dispatch.domain.optimizer.PlanningProblem;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.StopMerger;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.ToLongFunction;

/**
 * <strong>이보다 적은 고정비로는 이 수요를 실을 수 없다</strong> — 완화 문제의 하한 (DESIGN.md §6.9).
 *
 * <h2>무엇을 버리고 재는가</h2>
 * 기하 · 시간 · 방문 순서를 전부 버리고 <strong>총량만</strong> 본다. 남는 질문은 하나다 —
 * 「이 수요를 담을 수 있는 가장 싼 함대는 얼마인가」. 실제 계획은 여기에 더해 거리와 시간과
 * 약속창까지 만족시켜야 하므로, <em>어떤 알고리즘도</em> 이 값 아래로 내려갈 수 없다.
 *
 * <p>축은 <strong>제약 조합 × 자원</strong>이다. 제약 조합(냉장 · 위험물)은 «그 능력을 최소한
 * 요구하는 수요»와 «그 능력을 모두 갖춘 차량»을 맞대고, 자원은 하드 용량 축 셋
 * (stop 슬롯 · 중량 · 부피)이다. 축마다 따로 덮어 보고 <strong>가장 비싼 축</strong>을 쓴다 —
 * 실제 해는 모든 축을 <em>동시에</em> 덮어야 하므로, 한 축만 덮는 비용보다 쌀 수 없다.
 *
 * <p>각 축은 <strong>분수 허용</strong>으로 덮는다(단위 용량당 고정비가 싼 차부터, 마지막 한 대는
 * 쪼개서). 정수로 올리면 더 큰 값이 나오지만 그건 이미 하한이 아니라 «한 축만 본 최적해» 라
 * 다른 축과 겹칠 때 하한이 깨질 수 있다. <strong>느슨하더라도 참인 쪽</strong>을 고른다.
 *
 * <h2>이 값이 말하지 <em>않는</em> 것 — 총비용의 하한이 아니다</h2>
 * 고정비의 하한이지 <strong>총비용의 하한이 아니다.</strong> 2026-09-12 에 그것을 측정으로
 * 배웠다([ADR-038]): 배정을 촘촘하게 바꿔 `large` 의 고정비를 2,500,000 → 2,090,000 (하한
 * 2,020,000 의 코앞)까지 내렸더니 <strong>미배정 페널티가 30,000 → 1,660,000</strong> 으로
 * 올랐다. 아낀 고정비 1원마다 4원을 문 것이다 — 빈 좌석은 낭비가 아니라 희소 능력(위험물·냉장)
 * 수요가 나중에 앉을 자리이기 때문이다. 이 열은 «얼마나 멀리 있는가» 를 말하지 «가야 한다» 를
 * 말하지 않는다.
 *
 * @param krw         하한(원). {@code feasible()} 이 거짓이면 뜻이 없다
 * @param bindingAxis 그 값을 만든 축의 이름
 * @param uncoverable 전 차량으로도 덮지 못하는 축들. 비어 있지 않으면 이 데이터셋은 실현 불가다
 */
public record FixedCostFloor(long krw, String bindingAxis, List<String> uncoverable) {

    public FixedCostFloor {
        Objects.requireNonNull(bindingAxis, "bindingAxis");
        uncoverable = List.copyOf(Objects.requireNonNull(uncoverable, "uncoverable"));
    }

    /** 모든 축을 덮을 수 있는가. 거짓이면 수요가 함대를 넘는다(`overload` 가 그렇다). */
    public boolean feasible() {
        return uncoverable.isEmpty();
    }

    /**
     * 문제에서 잰다.
     *
     * @param problem 계획 입력. 룰셋에서 stop 상한을 읽는다
     */
    public static FixedCostFloor of(PlanningProblem problem) {
        Objects.requireNonNull(problem, "problem");
        List<Stop> stops = StopMerger.merge(problem.candidates());
        OptionalInt stopCap = problem.rules().routeStopCap();

        long best = 0L;
        String binding = "—";
        List<String> uncoverable = new ArrayList<>();

        // 조합을 손으로 나열하지 않는다 — 모델의 축에서 뽑는다([ADR-033] 의 ConstraintClass).
        for (ConstraintClass axis : ConstraintClass.all()) {
            // 여기서 조합은 «정확히 이 조합» 이 아니라 «최소한 이것을 요구하는» <strong>문턱</strong>
            // 이다. 하한은 「그 능력을 요구하는 수요」와 「그 능력을 갖춘 차량」을 맞대야 참이 된다 —
            // 좌석 예약([ADR-039])이 조합을 <em>정확히</em> 보는 것과 다른 쓰임이라 이름도 다르다.
            List<Stop> demand = stops.stream()
                    .filter(stop -> ConstraintClass.of(stop).covers(axis))
                    .toList();
            if (demand.isEmpty()) {
                continue;
            }
            List<VehicleSpec> fleet = problem.vehicles().stream().filter(axis::carriedBy).toList();
            String label = describe(axis);

            if (stopCap.isPresent()) {
                long slots = stopCap.getAsInt();
                long bound = cover(demand.size(), fleet, vehicle -> slots);
                if (bound < 0) {
                    uncoverable.add(label + " stop");
                } else if (bound > best) {
                    best = bound;
                    binding = label + " stop";
                }
            }
            long weight = demand.stream().mapToLong(stop -> stop.parcel().weightG()).sum();
            long byWeight = cover(weight, fleet, vehicle -> vehicle.capacity().maxWeightG());
            if (byWeight < 0) {
                uncoverable.add(label + " 중량");
            } else if (byWeight > best) {
                best = byWeight;
                binding = label + " 중량";
            }
            long volume = demand.stream().mapToLong(stop -> stop.parcel().volumeCm3()).sum();
            long byVolume = cover(volume, fleet, vehicle -> vehicle.capacity().maxVolumeCm3());
            if (byVolume < 0) {
                uncoverable.add(label + " 부피");
            } else if (byVolume > best) {
                best = byVolume;
                binding = label + " 부피";
            }
        }
        return new FixedCostFloor(best, binding, uncoverable);
    }

    /**
     * 이 축 하나를 덮는 최소 고정비 — <strong>단위 용량당 고정비가 싼 차부터</strong>, 마지막
     * 한 대는 분수로. 전 차량으로도 못 덮으면 {@code -1}.
     *
     * <p>정렬이 결정적이어야 같은 문제가 같은 하한을 낸다(불변규칙 12). 비율이 같으면 입력
     * 순서가 남는다 — {@code sorted} 가 안정 정렬이기 때문이다.
     */
    private static long cover(long demand, List<VehicleSpec> fleet,
            ToLongFunction<VehicleSpec> capacity) {

        List<VehicleSpec> sorted = fleet.stream()
                .filter(vehicle -> capacity.applyAsLong(vehicle) > 0L)
                .sorted(Comparator.comparingDouble(vehicle ->
                        (double) vehicle.cost().fixed().krw() / capacity.applyAsLong(vehicle)))
                .toList();

        double cost = 0.0d;
        long covered = 0L;
        for (VehicleSpec vehicle : sorted) {
            long slice = capacity.applyAsLong(vehicle);
            long fixed = vehicle.cost().fixed().krw();
            if (covered + slice >= demand) {
                return (long) Math.floor(cost + fixed * ((double) (demand - covered) / slice));
            }
            covered += slice;
            cost += fixed;
        }
        return -1L;
    }

    /** 문턱이 «아무것도 요구하지 않음» 이면 그 축의 수요는 <strong>전체</strong>다. */
    private static String describe(ConstraintClass axis) {
        return axis.isNone() ? "전체" : axis.label();
    }
}
