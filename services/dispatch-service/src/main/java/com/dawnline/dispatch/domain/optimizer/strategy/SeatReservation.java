package com.dawnline.dispatch.domain.optimizer.strategy;

import com.dawnline.dispatch.domain.optimizer.ConstraintClass;
import com.dawnline.dispatch.domain.optimizer.Feasibility;
import com.dawnline.dispatch.domain.optimizer.PlannedStop;
import com.dawnline.dispatch.domain.optimizer.RouteState;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleId;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * 희소한 제약 조합을 위해 <strong>좌석을 미리 비워 둔다</strong> ([ADR-039], DESIGN.md §6.5 3단계).
 *
 * <h2>무엇이 불완전했나</h2>
 * §6.5 3단계의 「한계비용이 가장 작은 차에 붙인다」는 <em>지금 붙이는 것</em>만 본다. 그래서
 * 냉장 ∧ 위험물 차량이 <strong>일반 수요로 먼저 찬다</strong> — 측정에서 그 조합 차량이 실은
 * stop 의 94~100%가 일반 수요였고, `small` 에서는 단 한 대의 조합 차량이 120 자리를 전부 일반
 * 수요로 채운 채 조합 수요 4건을 미배정으로 남겼다({@code docs/benchmarks/phase4-scarce-seats.md}).
 * 탐욕에는 「이 자리는 나중에 올 수요의 것」을 볼 눈이 없다.
 *
 * <h2>단위는 능력이 아니라 조합이다</h2>
 * 능력별로 예약하면 `peak` 에서 아무 일도 일어나지 않는다 — 위험물 차량의 여유 슬롯 287개가
 * 「충분하다」로 읽히기 때문이다. 실제로 모자란 것은 <strong>냉장 ∧ 위험물</strong> 9대의 여유
 * 5개다. 축은 {@link ConstraintClass}([ADR-033])다.
 *
 * <h2>네 가지를 지킨다</h2>
 * <ol>
 *   <li><strong>스냅샷</strong>. 통합 후 stop 의 조합을 <em>계획 시작 시점에</em> 한 번 센다.</li>
 *   <li><strong>비대칭</strong>. 덜 특정한 수요는 더 특정한 예약에 앉지 못한다. 반대 방향
 *       (냉장 ∧ 위험물 stop 이 냉장 예약에 앉는 것)은 <em>자기 버킷이 소진됐을 때만</em>이다.
 *       집계로 적으면 조합별 예약 합 = min(그 조합의 수요, 그 조합을 실을 수 있는 용량)이다.</li>
 *   <li><strong>풀린다</strong>. 예약은 배정 단계의 것이다. 기하 때문에 아무도 못 앉은 좌석은
 *       재삽입이 일반 수요로 채운다 — 그러지 않으면 [ADR-038] 이 배운 「빈 좌석」의 교훈을
 *       예약이 거꾸로 만든다(아무도 앉지 않는 자리를 비워 두는 것).</li>
 *   <li><strong>결정론</strong>. 조합은 특정한 것부터, 차량은 인덱스 순 라운드로빈이다.</li>
 * </ol>
 *
 * <h2>왜 라운드로빈인가</h2>
 * 앞 차부터 몰아서 예약하면 <strong>예약한 좌석에 아무도 앉지 못한다.</strong> 조합 수요는
 * 지도 전체에 흩어져 있어서 한 대의 근무창·약속창 안에 다 들어가지 않는다 — 좌석은 기하와
 * 함께 쓰여야 뜻이 있다. 그래서 한 바퀴에 한 자리씩, 차량 인덱스 순으로 돈다.
 */
final class SeatReservation {

    /** 예약된 좌석이라 일반 수요가 앉지 못한다는 사유 (§6.3 의 {@code RESERVED_SEAT}). */
    static final Feasibility RESERVED = Feasibility.violated("reserved-seat",
            "희소한 제약 조합의 수요를 위해 예약된 좌석입니다");

    private static final List<ConstraintClass> CLASSES = ConstraintClass.all();

    private static final Map<ConstraintClass, Integer> INDEX = IntStream.range(0, CLASSES.size())
            .boxed().collect(java.util.stream.Collectors.toUnmodifiableMap(CLASSES::get, i -> i));

    /** 덜 특정한 조합부터. 자유석을 나눌 때 <strong>덜 유연한 수요</strong>가 먼저다. */
    private static final int[] LEAST_SPECIFIC_FIRST = order(Comparator.naturalOrder());

    /** 더 특정한 조합부터. 예약을 걷을 때도, 예약을 나눌 때도 이 순서다. */
    private static final int[] MOST_SPECIFIC_FIRST = order(Comparator.reverseOrder());

    private final int stopCap;
    private final Map<VehicleId, int[]> reserved;

    /**
     * 예약 때문에 <strong>다른 차로 밀린</strong> stop 들. 설명(§6.3)이 이것을 읽는다.
     *
     * <p>시험 배치에서 막힌 것도 포함한다 — 「이 stop 은 적어도 한 대의 예약 좌석에 막혔다」가
     * 사실이기 때문이다. 순서를 유지해 같은 seed 가 같은 설명을 낸다(불변규칙 12).
     */
    private final Set<Stop> blocked = new LinkedHashSet<>();

    private SeatReservation(int stopCap, Map<VehicleId, int[]> reserved) {
        this.stopCap = stopCap;
        this.reserved = reserved;
    }

    /** 아무 자리도 예약하지 않는다. 룰이 stop 상한을 말하지 않으면 이것이다. */
    static SeatReservation none() {
        return new SeatReservation(0, Map.of());
    }

    /**
     * 수요와 함대에서 예약을 계산한다 — <strong>계획 시작 시점 스냅샷</strong>이다.
     *
     * <p>상한이 없으면 예약도 없다. 자리를 세지 않는 축에서는 좌석이 희소할 수 없기 때문이다 —
     * 중량·부피로 차는 차량은 이 축이 아니라 [ADR-038] 의 다른 축에서 막힌다.
     *
     * @param stops    통합 후 stop 들
     * @param vehicles 차량들. 예약을 나누는 순서가 이 목록의 순서다
     * @param stopCap  룰셋이 말하는 라우트당 stop 상한 ([ADR-038])
     */
    static SeatReservation of(List<Stop> stops, List<VehicleSpec> vehicles, OptionalInt stopCap) {
        Objects.requireNonNull(stops, "stops");
        Objects.requireNonNull(vehicles, "vehicles");
        Objects.requireNonNull(stopCap, "stopCap");
        if (stopCap.isEmpty() || stopCap.getAsInt() <= 0) {
            return none();
        }
        int cap = stopCap.getAsInt();

        int[] demand = new int[CLASSES.size()];
        for (Stop stop : stops) {
            demand[INDEX.get(ConstraintClass.of(stop))]++;
        }

        Map<VehicleId, int[]> reserved = new LinkedHashMap<>();
        int[] used = new int[vehicles.size()];
        for (int k : MOST_SPECIFIC_FIRST) {
            ConstraintClass klass = CLASSES.get(k);
            // 일반 수요에는 예약하지 않는다 — 아무도 막지 않는 예약이라 항등이다.
            // 그리고 더 특정한 조합을 먼저 나눈다: 순서를 뒤집으면 흔한 수요가 희소한 차의
            // 자리를 먼저 예약해 버린다.
            if (klass.isNone() || demand[k] == 0) {
                continue;
            }
            spread(klass, demand[k], vehicles, cap, used, reserved, k);
        }
        return new SeatReservation(cap, Map.copyOf(reserved));
    }

    /** 한 바퀴에 한 자리씩, 차량 인덱스 순으로. 수요를 다 나눴거나 자리가 없으면 멈춘다. */
    private static void spread(ConstraintClass klass, int demand, List<VehicleSpec> vehicles,
            int cap, int[] used, Map<VehicleId, int[]> reserved, int classIndex) {

        int need = demand;
        boolean progressed = true;
        while (need > 0 && progressed) {
            progressed = false;
            for (int v = 0; v < vehicles.size() && need > 0; v++) {
                VehicleSpec vehicle = vehicles.get(v);
                if (used[v] >= cap || !klass.carriedBy(vehicle)) {
                    continue;
                }
                reserved.computeIfAbsent(vehicle.id(), id -> new int[CLASSES.size()])[classIndex]++;
                used[v]++;
                need--;
                progressed = true;
            }
        }
    }

    /** 예약된 자리가 하나라도 있는가. */
    boolean active() {
        return !reserved.isEmpty();
    }

    /**
     * 이 차량의 이 조합 예약 좌석 수.
     *
     * @param vehicle 차량 id
     * @param klass   제약 조합
     */
    int reservedFor(VehicleId vehicle, ConstraintClass klass) {
        int[] seats = reserved.get(vehicle);
        return seats == null ? 0 : seats[INDEX.get(klass)];
    }

    /**
     * 함대 전체에서 이 조합에 예약된 좌석 수.
     *
     * @param klass 제약 조합
     */
    int totalFor(ConstraintClass klass) {
        int index = INDEX.get(klass);
        return reserved.values().stream().mapToInt(seats -> seats[index]).sum();
    }

    /** 예약 때문에 다른 차로 밀린 stop 인가. */
    boolean blocked(Stop stop) {
        return blocked.contains(stop);
    }

    /**
     * 이 라우트의 문. 지금 실려 있는 stop 들을 조합별로 세어 시작한다.
     *
     * @param state 라우트 상태
     */
    SeatGate gateFor(RouteState state) {
        Objects.requireNonNull(state, "state");
        int[] seats = reserved.get(state.vehicle().id());
        if (seats == null) {
            return SeatGate.OPEN;           // 이 차에는 예약이 없다 — 셀 것도 없다
        }
        int[] seated = new int[CLASSES.size()];
        for (PlannedStop planned : state.stops()) {
            seated[INDEX.get(ConstraintClass.of(planned.stop()))]++;
        }
        return new Gate(seated, seats, stopCap, blocked);
    }

    private static int[] order(Comparator<Integer> bySpecificity) {
        return IntStream.range(0, CLASSES.size()).boxed()
                .sorted(Comparator.comparing(
                        (Integer i) -> CLASSES.get(i).specificity(), bySpecificity))
                .mapToInt(Integer::intValue)
                .toArray();
    }

    /**
     * 라우트 하나의 좌석 셈.
     *
     * <p>판정은 <strong>지금 실려 있는 조합별 수</strong>의 순수 함수다. 소비 기록을 따로 두지
     * 않는 이유는 시험 배치({@code branch}) 와 확정 배치가 <em>같은 답</em>을 내야 하기 때문이다 —
     * 상태를 들면 둘이 어긋날 수 있고, 어긋나면 「왜 이 차인가」에 답할 수 없게 된다.
     */
    private static final class Gate implements SeatGate {

        private final int[] seated;
        private final int[] reserved;
        private final int cap;
        private final Set<Stop> blocked;

        private Gate(int[] seated, int[] reserved, int cap, Set<Stop> blocked) {
            this.seated = seated;
            this.reserved = reserved;
            this.cap = cap;
            this.blocked = blocked;
        }

        @Override
        public Feasibility admits(Stop stop) {
            int index = INDEX.get(ConstraintClass.of(stop));
            seated[index]++;
            boolean fits = fits();
            seated[index]--;
            if (fits) {
                return Feasibility.ok();
            }
            blocked.add(stop);
            return RESERVED;
        }

        @Override
        public void seat(Stop stop) {
            seated[INDEX.get(ConstraintClass.of(stop))]++;
        }

        /**
         * 지금 실린 stop 들을 좌석에 <strong>짝지을 수 있는가</strong>.
         *
         * <p>탐욕 셋이다. ① 각 조합은 자기 버킷을 먼저 쓴다. ② 남은 수요는 자유석을 <em>덜
         * 유연한 쪽부터</em> 가져간다 — 일반 수요는 자유석 말고 갈 곳이 없고, 조합 수요는 아직
         * 걷을 예약이 남아 있다. ③ 그러고도 남은 조합 수요만 <em>자기가 포함하는</em> 조합의
         * 예약을 걷는다. 이 순서가 「덜 특정한 수요는 더 특정한 예약을 소비하지 못한다」와
         * 「반대 방향은 자기 버킷이 소진됐을 때만」을 그대로 옮긴 것이다.
         */
        private boolean fits() {
            int[] left = seated.clone();
            int[] rest = reserved.clone();
            int free = cap;
            for (int seats : reserved) {
                free -= seats;
            }
            for (int k = 0; k < left.length; k++) {
                int use = Math.min(left[k], rest[k]);
                left[k] -= use;
                rest[k] -= use;
            }
            for (int k : LEAST_SPECIFIC_FIRST) {
                int use = Math.min(left[k], Math.max(0, free));
                left[k] -= use;
                free -= use;
            }
            for (int k : MOST_SPECIFIC_FIRST) {
                for (int b : MOST_SPECIFIC_FIRST) {
                    if (k == b || !CLASSES.get(k).covers(CLASSES.get(b))) {
                        continue;
                    }
                    int use = Math.min(left[k], rest[b]);
                    left[k] -= use;
                    rest[b] -= use;
                }
            }
            for (int remaining : left) {
                if (remaining > 0) {
                    return false;
                }
            }
            return true;
        }
    }
}
