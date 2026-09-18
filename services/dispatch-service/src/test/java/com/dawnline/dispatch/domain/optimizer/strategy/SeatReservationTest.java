package com.dawnline.dispatch.domain.optimizer.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.domain.optimizer.CampDepot;
import com.dawnline.dispatch.domain.optimizer.Capacity;
import com.dawnline.dispatch.domain.optimizer.ConstraintClass;
import com.dawnline.dispatch.domain.optimizer.HaversineDistance;
import com.dawnline.dispatch.domain.optimizer.OrderId;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.RouteState;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleAttrs;
import com.dawnline.dispatch.domain.optimizer.VehicleCost;
import com.dawnline.dispatch.domain.optimizer.VehicleId;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 좌석 예약의 불변식 ([ADR-039]).
 *
 * <p>재는 것은 넷이다 — <strong>스냅샷</strong>(수요와 용량의 작은 쪽), <strong>비대칭</strong>
 * (덜 특정한 수요는 더 특정한 예약에 앉지 못한다), <strong>분산</strong>(한 대에 몰지 않는다),
 * 그리고 <strong>일반 수요를 뺀 이유</strong>.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("SeatReservation — 좌석은 조합 단위로 예약한다")
class SeatReservationTest {

    private static final Instant START = Instant.parse("2026-09-06T01:00:00Z");
    private static final GeoPoint CAMP = GeoPoint.of(37.5663, 126.9779);
    private static final CampDepot DEPOT = new CampDepot(Ids.newId(), CAMP);
    private static final TimeWindow WINDOW = new TimeWindow(START, START.plus(Duration.ofHours(8)));
    private static final HaversineDistance DISTANCE = new HaversineDistance(1.3d, 25.0d);

    private static final ConstraintClass NONE = ConstraintClass.NONE;
    private static final ConstraintClass COLD = new ConstraintClass(true, false);
    private static final ConstraintClass COMBO = new ConstraintClass(true, true);

    @Test
    void 상한을_말하는_룰이_없으면_예약도_없다() {
        // 전제를 먼저 말한다 — 상한이 없는 룰셋이어야 이 테스트가 무엇인가를 검사한다.
        assertThat(RuleSet.empty().routeStopCap())
                .as("전제: 이 룰셋은 stop 상한을 말하지 않는다")
                .isEmpty();

        SeatReservation seats = SeatReservation.of(List.of(stop(COMBO)),
                List.of(vehicle(true, true)), RuleSet.empty().routeStopCap());

        assertThat(seats.active())
                .as("자리를 세지 않는 축에서는 좌석이 희소할 수 없다")
                .isFalse();
    }

    @Test
    void 조합별_예약은_수요와_용량_중_작은_쪽이다() {
        VehicleSpec combo = vehicle(true, true);

        SeatReservation demandBound = SeatReservation.of(
                List.of(stop(COMBO), stop(COMBO)), List.of(combo), OptionalInt.of(5));
        assertThat(demandBound.totalFor(COMBO))
                .as("수요가 용량보다 작으면 수요만큼")
                .isEqualTo(2);

        SeatReservation capacityBound = SeatReservation.of(
                List.of(stop(COMBO), stop(COMBO), stop(COMBO), stop(COMBO)),
                List.of(combo), OptionalInt.of(3));
        assertThat(capacityBound.totalFor(COMBO))
                .as("용량이 수요보다 작으면 용량만큼 — 없는 자리를 예약하지 않는다")
                .isEqualTo(3);
    }

    @Test
    void 일반_수요에는_예약하지_않는다() {
        // 일반 좌석은 어느 수요도 막지 않으므로 그 예약은 항등이다([ADR-039]). 「잊은 것」이
        // 아니라 「뺀 것」이라는 기록이다.
        SeatReservation seats = SeatReservation.of(
                List.of(stop(NONE), stop(NONE)), List.of(vehicle(false, false)), OptionalInt.of(5));

        assertThat(seats.totalFor(NONE)).isZero();
        assertThat(seats.active()).isFalse();
    }

    @Test
    void 예약은_차량_인덱스로_돌아가며_나뉜다() {
        // 앞 차부터 몰아서 예약하면 예약한 좌석에 아무도 앉지 못한다 — 조합 수요는 지도
        // 전체에 흩어져 있어 한 대의 근무창 안에 다 들어가지 않기 때문이다.
        VehicleSpec first = vehicle(true, true);
        VehicleSpec second = vehicle(true, true);

        SeatReservation seats = SeatReservation.of(
                List.of(stop(COMBO), stop(COMBO), stop(COMBO)),
                List.of(first, second), OptionalInt.of(10));

        assertThat(seats.reservedFor(first.id(), COMBO)).isEqualTo(2);
        assertThat(seats.reservedFor(second.id(), COMBO)).isEqualTo(1);
    }

    @Test
    void 더_특정한_조합이_희소한_차의_자리를_먼저_잡는다() {
        // 냉장 수요가 조합 차량의 자리를 먼저 채우면 조합 수요가 갈 곳을 잃는다.
        VehicleSpec combo = vehicle(true, true);
        VehicleSpec coldOnly = vehicle(true, false);

        SeatReservation seats = SeatReservation.of(
                List.of(stop(COLD), stop(COLD), stop(COMBO), stop(COMBO)),
                List.of(combo, coldOnly), OptionalInt.of(2));

        assertThat(seats.reservedFor(combo.id(), COMBO))
                .as("조합 수요가 유일하게 앉을 수 있는 차의 자리를 먼저 가져간다")
                .isEqualTo(2);
        assertThat(seats.reservedFor(combo.id(), COLD))
                .as("남은 자리가 없으므로 냉장 예약은 이 차에 붙지 않는다")
                .isZero();
        assertThat(seats.reservedFor(coldOnly.id(), COLD)).isEqualTo(2);
    }

    @Test
    void 일반_수요는_예약된_좌석에_앉지_못한다() {
        VehicleSpec combo = vehicle(true, true);
        SeatReservation seats =
                SeatReservation.of(List.of(stop(COMBO)), List.of(combo), OptionalInt.of(3));
        SeatGate gate = seats.gateFor(RouteState.empty(combo, DEPOT, DISTANCE, START));

        Stop ordinary = stop(NONE);
        assertThat(gate.admits(ordinary).feasible()).as("자유석 2개 중 첫째").isTrue();
        gate.seat(stop(NONE));
        assertThat(gate.admits(ordinary).feasible()).as("자유석 2개 중 둘째").isTrue();
        gate.seat(stop(NONE));

        var refused = gate.admits(ordinary);
        assertThat(refused.feasible()).isFalse();
        assertThat(refused.ruleName())
                .as("설명(§6.3)이 「용량 초과」가 아니라 「예약된 자리」라고 말해야 한다")
                .isEqualTo("reserved-seat");
        assertThat(gate.admits(stop(COMBO)).feasible())
                .as("남은 한 자리는 그 조합의 것이다")
                .isTrue();
        assertThat(seats.blocked(ordinary))
                .as("밀린 stop 은 설명에 남길 수 있게 기록된다")
                .isTrue();
    }

    @Test
    void 조합_수요는_자기_버킷이_소진된_뒤에만_냉장_예약에_앉는다() {
        VehicleSpec combo = vehicle(true, true);
        SeatReservation seats = SeatReservation.of(
                List.of(stop(COMBO), stop(COLD), stop(COLD)), List.of(combo), OptionalInt.of(4));
        assertThat(seats.reservedFor(combo.id(), COMBO)).isEqualTo(1);
        assertThat(seats.reservedFor(combo.id(), COLD)).isEqualTo(2);

        SeatGate gate = seats.gateFor(RouteState.empty(combo, DEPOT, DISTANCE, START));
        for (int seated = 0; seated < 4; seated++) {
            assertThat(gate.admits(stop(COMBO)).feasible())
                    .as("%d번째 조합 수요 — 자기 버킷 → 자유석 → 냉장 예약 순으로 앉는다", seated + 1)
                    .isTrue();
            gate.seat(stop(COMBO));
        }
        assertThat(gate.admits(stop(COMBO)).feasible()).as("상한을 넘지는 않는다").isFalse();
    }

    @Test
    void 냉장_수요는_조합_예약에_앉지_못한다() {
        VehicleSpec combo = vehicle(true, true);
        SeatReservation seats = SeatReservation.of(
                List.of(stop(COMBO), stop(COLD), stop(COLD)), List.of(combo), OptionalInt.of(4));

        SeatGate gate = seats.gateFor(RouteState.empty(combo, DEPOT, DISTANCE, START));
        for (int seated = 0; seated < 3; seated++) {
            assertThat(gate.admits(stop(COLD)).feasible())
                    .as("%d번째 냉장 수요 — 냉장 예약 둘과 자유석 하나", seated + 1)
                    .isTrue();
            gate.seat(stop(COLD));
        }
        assertThat(gate.admits(stop(COLD)).feasible())
                .as("남은 한 자리는 조합의 것이다 — 반대 방향은 열리지 않는다")
                .isFalse();
    }

    private static Stop stop(ConstraintClass klass) {
        return new Stop(CAMP, List.of(OrderId.of(Ids.newId())),
                new Parcel(1, 1, klass.cold(), klass.hazmat()), WINDOW, 60, 0);
    }

    private static VehicleSpec vehicle(boolean cold, boolean hazmat) {
        return new VehicleSpec(VehicleId.of(Ids.newId()), new Capacity(1_000_000, 10_000_000),
                new VehicleAttrs("VAN", cold, hazmat),
                new TimeWindow(START, START.plus(Duration.ofHours(10))),
                VehicleCost.krw(45_000, 600, 250));
    }
}
