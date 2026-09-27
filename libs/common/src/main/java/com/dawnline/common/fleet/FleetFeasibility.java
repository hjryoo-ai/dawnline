package com.dawnline.common.fleet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * 함대가 수요를 실을 수 있는가 — 그리고 모자라면 <strong>몇 대</strong>가 모자라는가 (DESIGN.md §5.3 「함대」 · §6.9,
 * ADR-033 · ADR-067).
 *
 * <h2>기준</h2>
 * 제약 조합(냉장 × 위험물 × 대형)마다, 그 조합을 <em>최소한</em> 요구하는 수요가 그 조합을 <em>모두</em> 갖춘 차량 용량의
 * <strong>80% 이하</strong>다 — stop · 중량 · 부피 세 축에서. 「대형」은 가장 작은 차량에 들어가지 않는 stop 이다. stop 축의 용량은
 * 라우트당 stop 상한이고, 상한이 없으면 그 축은 재지 않는다(ADR-039 결정 2 — 자리를 세지 않는 축에서는 자리가 희소할 수 없다).
 * 비교는 정수다: {@code 100 × 수요 ≤ 80 × 용량}.
 *
 * <p>수요는 <strong>통합 후 stop</strong> 이어야 한다 — 통합은 제약을 전파한다(한 건이 위험물이면 stop 전체가 위험물). 원본 주문을
 * 넣으면 그 전파가 기준에서 빠진다. 통합은 부르는 쪽의 일이다(dispatch 의 {@code StopMerger}) — 이 클래스는 통합 키를 모른다.
 *
 * <h2>부족분은 가장 특정한 조합부터</h2>
 * 요구하는 능력이 많은 조합부터 재고, 앞에서 더한 차량은 <strong>그것이 덮는 모든 조합의 용량에 더해진 채로</strong> 다음 조합을
 * 잰다 — 냉장 ∧ 위험물 차량 한 대는 냉장 · 위험물 · 일반 조합의 용량이기도 하다. ADR-039 불변식 2 가 좌석을 특정한 조합부터 나눈
 * 것의 거울이다: 거기서는 뒤집으면 흔한 수요가 희소한 자리를 가져가고, 여기서는 뒤집으면 일반 조합이 먼저 산 용량을 특정한 조합이
 * 한 번 더 산다.
 *
 * <h2>템플릿이 없으면 값이 아니다</h2>
 * 더하는 차량은 그 함대에서 냉장 · 위험물이 그 조합과 <em>정확히</em> 같은(대형 조합이면 대형인) 가장 싼 차량의 사본이다. 그런
 * 차량이 없으면 부족 대수를 잴 단위가 없으므로 {@link Status#NO_TEMPLATE} 이고 {@code shortfall} 은 {@code null} 이다 —
 * 0 으로 접으면 부르는 쪽이 조용히 건너뛴다.
 *
 * <p>프레임워크 비의존이고 dispatch 의 도메인 타입을 모른다(ADR-049) — 벤치마크({@code DatasetFeasibilityTest})와
 * dispatch({@code GET /waves/{waveId}/fleet-feasibility})가 같은 코드를 쓰게 하려고 여기 있다.
 */
public final class FleetFeasibility {

    /** 여유 — 수요는 용량의 이 퍼센트 이하여야 한다 (ADR-033). */
    public static final int HEADROOM_PERCENT = 80;

    private static final long PERCENT = 100L;

    private FleetFeasibility() {
        throw new AssertionError("유틸리티 클래스는 생성하지 않는다");
    }

    /**
     * 수요와 함대를 맞댄다.
     *
     * @param stops             통합 후 stop
     * @param fleet             계획이 쓸 수 있는 차량
     * @param maxStopsPerRoute  라우트당 stop 상한. {@code null} 이면 stop 축을 재지 않는다
     * @return 조합마다 한 줄, 잰 순서(가장 특정한 조합부터)
     */
    public static Assessment assess(List<Stop> stops, List<Vehicle> fleet, @Nullable Integer maxStopsPerRoute) {
        Objects.requireNonNull(stops, "stops");
        Objects.requireNonNull(fleet, "fleet");
        if (maxStopsPerRoute != null && maxStopsPerRoute <= 0) {
            throw new IllegalArgumentException("라우트당 stop 상한은 양수여야 한다: " + maxStopsPerRoute);
        }
        @Nullable Size smallest = fleet.stream()
                .map(vehicle -> new Size(vehicle.maxWeightG(), vehicle.maxVolumeCm3()))
                .min(Comparator.comparingLong(size -> size.weightG() + size.volumeCm3()))
                .orElse(null);

        List<Vehicle> added = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        for (Combination combination : Combination.bySpecificity()) {
            Line line = measure(combination, stops, fleet, added, smallest, maxStopsPerRoute);
            lines.add(line);
            if (line.status() == Status.SHORTFALL) {
                for (int i = 0; i < line.shortfall(); i++) {
                    added.add(Objects.requireNonNull(line.template()));
                }
            }
        }
        return new Assessment(List.copyOf(lines), stops.size(), maxStopsPerRoute, smallest);
    }

    private static Line measure(Combination combination, List<Stop> stops, List<Vehicle> fleet, List<Vehicle> added,
            @Nullable Size smallest, @Nullable Integer maxStops) {
        long demandStops = 0;
        long demandWeight = 0;
        long demandVolume = 0;
        for (Stop stop : stops) {
            if (combination.demandedBy(stop, smallest)) {
                demandStops++;
                demandWeight += stop.weightG();
                demandVolume += stop.volumeCm3();
            }
        }
        Load demand = new Load(demandStops, demandWeight, demandVolume);

        List<Vehicle> carriers = fleet.stream().filter(v -> combination.carriedBy(v, smallest)).toList();
        Load capacity = capacity(carriers, maxStops);
        Load before = capacity;
        for (Vehicle vehicle : added) {
            if (combination.carriedBy(vehicle, smallest)) {
                capacity = capacity.plus(capacity(List.of(vehicle), maxStops));
            }
        }

        @Nullable Vehicle template = fleet.stream()
                .filter(v -> combination.templateFor(v, smallest))
                .min(Comparator.comparingLong(Vehicle::fixedCostKrw).thenComparing(Vehicle::code))
                .orElse(null);

        if (fits(demand, capacity, maxStops != null)) {
            return new Line(combination, demand, before, carriers.size(), capacity, 0, template, Status.FEASIBLE);
        }
        if (template == null) {
            return new Line(combination, demand, before, carriers.size(), capacity, null, null, Status.NO_TEMPLATE);
        }
        Load unit = capacity(List.of(template), maxStops);
        long needed = Math.max(needed(demand.weightG(), capacity.weightG(), unit.weightG()),
                needed(demand.volumeCm3(), capacity.volumeCm3(), unit.volumeCm3()));
        if (maxStops != null) {
            needed = Math.max(needed, needed(demand.stops(), capacity.stops(), unit.stops()));
        }
        return new Line(combination, demand, before, carriers.size(), capacity, Math.toIntExact(needed), template,
                Status.SHORTFALL);
    }

    private static Load capacity(List<Vehicle> vehicles, @Nullable Integer maxStops) {
        long weight = 0;
        long volume = 0;
        for (Vehicle vehicle : vehicles) {
            weight += vehicle.maxWeightG();
            volume += vehicle.maxVolumeCm3();
        }
        return new Load(maxStops == null ? 0 : (long) vehicles.size() * maxStops, weight, volume);
    }

    private static boolean fits(Load demand, Load capacity, boolean countStops) {
        return within(demand.weightG(), capacity.weightG())
                && within(demand.volumeCm3(), capacity.volumeCm3())
                && (!countStops || within(demand.stops(), capacity.stops()));
    }

    /** {@code 100 × 수요 ≤ 80 × 용량}. 수요가 0 이면 용량이 0 이어도 참이다 — 잴 것이 없다. */
    private static boolean within(long demand, long capacity) {
        return PERCENT * demand <= HEADROOM_PERCENT * capacity;
    }

    /** 한 축에서 {@code 100 × 수요 ≤ 80 × (용량 + n × 단위)} 를 만드는 가장 작은 n. */
    private static long needed(long demand, long capacity, long unit) {
        long gap = PERCENT * demand - HEADROOM_PERCENT * capacity;
        if (gap <= 0) {
            return 0;
        }
        long per = HEADROOM_PERCENT * unit;
        return (gap + per - 1) / per;
    }

    /**
     * 통합 후 stop 하나.
     *
     * @param cold      냉장을 요구하는가 (통합 후 — 한 건이라도 냉장이면 참)
     * @param hazmat    위험물인가 (같다)
     * @param weightG   중량(g)
     * @param volumeCm3 부피(㎤)
     */
    public record Stop(boolean cold, boolean hazmat, long weightG, long volumeCm3) {
    }

    /**
     * 계획이 쓸 수 있는 차량 한 대.
     *
     * @param id           차량 id — 템플릿을 부르는 쪽이 자기 행으로 되찾는다
     * @param code         운영자가 부르는 이름 — 고정비가 같은 템플릿의 순서
     * @param cold         냉장 차량인가
     * @param hazmat       위험물 허용인가
     * @param maxWeightG   최대 중량(g)
     * @param maxVolumeCm3 최대 부피(㎤)
     * @param fixedCostKrw 고정비 — 템플릿은 가장 싼 것
     */
    public record Vehicle(String id, String code, boolean cold, boolean hazmat, int maxWeightG, int maxVolumeCm3,
            long fixedCostKrw) {
        public Vehicle {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(code, "code");
        }
    }

    /**
     * 세 축의 양 — 수요이거나 용량이다.
     *
     * @param stops     stop 수 (용량이면 차량 수 × 상한, 상한이 없으면 0)
     * @param weightG   중량(g)
     * @param volumeCm3 부피(㎤)
     */
    public record Load(long stops, long weightG, long volumeCm3) {
        Load plus(Load other) {
            return new Load(stops + other.stops, weightG + other.weightG, volumeCm3 + other.volumeCm3);
        }
    }

    /**
     * 가장 작은 차량의 크기. 「대형」은 여기 들어가지 않는 stop 이고, 이것보다 한 축이라도 큰 차량이다.
     *
     * @param weightG   중량(g)
     * @param volumeCm3 부피(㎤)
     */
    public record Size(long weightG, long volumeCm3) {
    }

    /**
     * 제약 조합. 손으로 나열하지 않고 세 축의 곱으로 만든다 — 축이 늘면 조합이 따라 는다.
     *
     * @param cold   냉장
     * @param hazmat 위험물
     * @param large  대형 — 가장 작은 차량에 들어가지 않는다
     */
    public record Combination(boolean cold, boolean hazmat, boolean large) {

        private static final List<Combination> BY_SPECIFICITY = enumerate();

        /** 모든 조합, 요구하는 능력이 많은 것부터. 같으면 축의 순서(냉장 → 위험물 → 대형) — 재현성(불변규칙 12). */
        public static List<Combination> bySpecificity() {
            return BY_SPECIFICITY;
        }

        /** 요구하는 능력의 수. */
        public int specificity() {
            return (cold ? 1 : 0) + (hazmat ? 1 : 0) + (large ? 1 : 0);
        }

        /** 이 stop 이 이 조합의 능력을 <em>최소한</em> 요구하는가. */
        boolean demandedBy(Stop stop, @Nullable Size smallest) {
            return (!cold || stop.cold()) && (!hazmat || stop.hazmat()) && (!large || isLarge(stop, smallest));
        }

        /** 이 차량이 이 조합의 능력을 <em>모두</em> 갖췄는가. */
        boolean carriedBy(Vehicle vehicle, @Nullable Size smallest) {
            return (!cold || vehicle.cold()) && (!hazmat || vehicle.hazmat())
                    && (!large || isLarge(vehicle, smallest));
        }

        /** 템플릿 — 냉장 · 위험물이 정확히 같고, 대형 조합이면 대형. 크기는 「정확히」에 넣지 않는다(야간조의 냉장은 트럭뿐이다). */
        boolean templateFor(Vehicle vehicle, @Nullable Size smallest) {
            return vehicle.cold() == cold && vehicle.hazmat() == hazmat && (!large || isLarge(vehicle, smallest));
        }

        /** 사람이 읽을 이름 — 리포트와 로그가 같은 말을 쓴다. */
        public String label() {
            List<String> parts = new ArrayList<>();
            if (cold) {
                parts.add("냉장");
            }
            if (hazmat) {
                parts.add("위험물");
            }
            if (large) {
                parts.add("대형");
            }
            return parts.isEmpty() ? "일반" : String.join("∧", parts);
        }

        private static boolean isLarge(Stop stop, @Nullable Size smallest) {
            return smallest != null && (stop.weightG() > smallest.weightG() || stop.volumeCm3() > smallest.volumeCm3());
        }

        private static boolean isLarge(Vehicle vehicle, @Nullable Size smallest) {
            return smallest != null
                    && (vehicle.maxWeightG() > smallest.weightG() || vehicle.maxVolumeCm3() > smallest.volumeCm3());
        }

        private static List<Combination> enumerate() {
            List<Combination> all = new ArrayList<>();
            for (boolean cold : new boolean[] {false, true}) {
                for (boolean hazmat : new boolean[] {false, true}) {
                    for (boolean large : new boolean[] {false, true}) {
                        all.add(new Combination(cold, hazmat, large));
                    }
                }
            }
            all.sort(Comparator.comparingInt(Combination::specificity).reversed());
            return List.copyOf(all);
        }
    }

    /** 조합 한 줄의 판정. */
    public enum Status {
        /** 세 축이 다 여유 안이다 — 앞 조합에서 더한 차량을 포함해서. */
        FEASIBLE,
        /** 템플릿을 {@code shortfall} 대 더하면 여유 안이 된다. */
        SHORTFALL,
        /** 여유를 넘는데 더할 템플릿이 없다 — 대수를 잴 단위가 없다. 값이 아니다. */
        NO_TEMPLATE
    }

    /**
     * 조합 한 줄.
     *
     * @param combination 조합
     * @param demand      이 조합을 최소한 요구하는 수요
     * @param capacity    이 조합을 모두 갖춘 기존 차량의 용량
     * @param vehicles    그 차량 수
     * @param withAdded   앞 조합에서 더한 차량까지 더한 용량 — 판정은 이것으로 했다
     * @param shortfall   더할 대수. {@link Status#NO_TEMPLATE} 이면 {@code null}
     * @param template    더할 차량의 원본. 없으면 {@code null}
     * @param status      판정
     */
    public record Line(Combination combination, Load demand, Load capacity, int vehicles, Load withAdded,
            @Nullable Integer shortfall, @Nullable Vehicle template, Status status) {
    }

    /**
     * 판정 전부.
     *
     * @param lines            조합마다 한 줄, 가장 특정한 조합부터
     * @param stops            통합 후 stop 수
     * @param maxStopsPerRoute stop 축의 상한. {@code null} 이면 재지 않았다
     * @param smallest         가장 작은 차량. 함대가 비었으면 {@code null}
     */
    public record Assessment(List<Line> lines, int stops, @Nullable Integer maxStopsPerRoute,
            @Nullable Size smallest) {

        /** 모든 조합이 여유 안인가 — 더할 것이 없다. */
        public boolean feasible() {
            return lines.stream().allMatch(line -> line.status() == Status.FEASIBLE);
        }

        /** 여유를 넘는 줄. */
        public List<Line> violations() {
            return lines.stream().filter(line -> line.status() != Status.FEASIBLE).toList();
        }

        /** 그 조합의 줄. */
        public Optional<Line> line(Combination combination) {
            return lines.stream().filter(line -> line.combination().equals(combination)).findFirst();
        }
    }
}
