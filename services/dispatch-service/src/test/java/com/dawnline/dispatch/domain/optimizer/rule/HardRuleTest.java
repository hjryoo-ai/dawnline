package com.dawnline.dispatch.domain.optimizer.rule;

import static com.dawnline.dispatch.domain.optimizer.rule.RuleFixtures.CITY_HALL;
import static com.dawnline.dispatch.domain.optimizer.rule.RuleFixtures.GANGNAM;
import static com.dawnline.dispatch.domain.optimizer.rule.RuleFixtures.PROMISED;
import static com.dawnline.dispatch.domain.optimizer.rule.RuleFixtures.START;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.TimeWindow;
import com.dawnline.common.error.ValidationException;
import com.dawnline.dispatch.domain.optimizer.Feasibility;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.RouteState;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleAttrs;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** §6.3 하드 룰 5종 — 각각 위반과 통과를 본다. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class HardRuleTest {

    @Nested
    @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
    class 냉장_위험물_매칭 {

        private final VehicleAttributeMatchRule cold =
                new VehicleAttributeMatchRule("cold-chain", 10, "requiresCold", "isCold");

        @Test
        void 냉장이_필요없으면_일반_차량도_통과한다() {
            Feasibility result = cold.check(RuleFixtures.stop(GANGNAM),
                    RuleFixtures.vehicle(), RuleFixtures.emptyRoute(RuleFixtures.vehicle()));

            assertThat(result.feasible()).isTrue();
        }

        @Test
        void 냉장_주문에_일반_차량이면_불가다() {
            Stop stop = RuleFixtures.stop(GANGNAM, new Parcel(1, 1, true, false), 0, 1);

            Feasibility result = cold.check(stop, RuleFixtures.vehicle(),
                    RuleFixtures.emptyRoute(RuleFixtures.vehicle()));

            assertThat(result.feasible()).isFalse();
            assertThat(result.ruleName()).isEqualTo("cold-chain");
        }

        @Test
        void 냉장_주문에_냉장_차량이면_통과한다() {
            Stop stop = RuleFixtures.stop(GANGNAM, new Parcel(1, 1, true, false), 0, 1);
            VehicleSpec fridge = RuleFixtures.vehicle(new VehicleAttrs("COLD_VAN", true, false));

            assertThat(cold.check(stop, fridge, RuleFixtures.emptyRoute(fridge)).feasible()).isTrue();
        }

        @Test
        void 통합된_stop_은_한_건만_냉장이어도_냉장차를_요구한다() {
            // Parcel.plus 가 OR 이므로 통합 결과가 이미 냉장이다.
            Parcel merged = new Parcel(1, 1, false, false).plus(new Parcel(1, 1, true, false));
            Stop stop = RuleFixtures.stop(GANGNAM, merged, 0, 2);

            assertThat(cold.check(stop, RuleFixtures.vehicle(),
                    RuleFixtures.emptyRoute(RuleFixtures.vehicle())).feasible()).isFalse();
        }

        @Test
        void 위험물도_같은_룰_타입으로_표현된다() {
            // 코드 두 벌이 아니라 정의 두 줄로 표현한다.
            VehicleAttributeMatchRule hazmat =
                    new VehicleAttributeMatchRule("hazmat", 11, "hazmat", "allowsHazmat");
            Stop stop = RuleFixtures.stop(GANGNAM, new Parcel(1, 1, false, true), 0, 1);
            VehicleSpec allowed = RuleFixtures.vehicle(new VehicleAttrs("TRUCK", false, true));

            assertThat(hazmat.check(stop, RuleFixtures.vehicle(),
                    RuleFixtures.emptyRoute(RuleFixtures.vehicle())).feasible()).isFalse();
            assertThat(hazmat.check(stop, allowed, RuleFixtures.emptyRoute(allowed)).feasible()).isTrue();
        }

        @Test
        void 알_수_없는_플래그는_룰을_만드는_시점에_거부한다() {
            assertThatThrownBy(() -> new VehicleAttributeMatchRule("x", 10, "fragile", "isCold"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("requiresCold");
        }
    }

    @Nested
    @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
    class 용량 {

        private final VehicleCapacityRule rule = new VehicleCapacityRule("capacity", 15);

        @Test
        void 누적이_용량_안이면_통과한다() {
            VehicleSpec vehicle = RuleFixtures.vehicle();
            Stop stop = RuleFixtures.stop(GANGNAM, new Parcel(1_000, 1_000, false, false), 0, 1);

            assertThat(rule.check(stop, vehicle, RuleFixtures.emptyRoute(vehicle)).feasible()).isTrue();
        }

        @Test
        void 이미_실린_것까지_더해서_본다() {
            // 빈 라우트만 보면 통과하는 stop 도, 앞의 적재를 더하면 넘칠 수 있다.
            VehicleSpec vehicle = RuleFixtures.vehicle();
            Stop half = RuleFixtures.stop(GANGNAM, new Parcel(600_000, 1, false, false), 0, 1);
            RouteState loaded = RuleFixtures.emptyRoute(vehicle).append(half);

            assertThat(rule.check(half, vehicle, RuleFixtures.emptyRoute(vehicle)).feasible()).isTrue();
            assertThat(rule.check(half, vehicle, loaded).feasible()).isFalse();
        }

        @Test
        void 사유에_실제_수치가_들어간다() {
            VehicleSpec vehicle = RuleFixtures.vehicle();
            Stop huge = RuleFixtures.stop(GANGNAM, new Parcel(2_000_000, 1, false, false), 0, 1);

            assertThat(rule.check(huge, vehicle, RuleFixtures.emptyRoute(vehicle)).reason())
                    .contains("2000000g");
        }
    }

    @Nested
    @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
    class stop_상한 {

        private final MaxStopsPerRouteRule rule = new MaxStopsPerRouteRule("max-stops", 20, 2);

        @Test
        void 상한_미만이면_통과한다() {
            VehicleSpec vehicle = RuleFixtures.vehicle();
            RouteState one = RuleFixtures.emptyRoute(vehicle).append(RuleFixtures.stop(GANGNAM));

            assertThat(rule.check(RuleFixtures.stop(CITY_HALL), vehicle, one).feasible()).isTrue();
        }

        @Test
        void 상한에_도달하면_더_넣지_않는다() {
            VehicleSpec vehicle = RuleFixtures.vehicle();
            RouteState two = RuleFixtures.emptyRoute(vehicle)
                    .append(RuleFixtures.stop(GANGNAM))
                    .append(RuleFixtures.stop(CITY_HALL));

            assertThat(rule.check(RuleFixtures.stop(GANGNAM), vehicle, two).feasible()).isFalse();
        }

        @Test
        void 상한이_0_이하면_거부한다() {
            assertThatThrownBy(() -> MaxStopsPerRouteRule.of(
                    RuleFixtures.definition("m", RuleType.MAX_STOPS_PER_ROUTE, 20, Map.of("max", 0))))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
    class 근무창 {

        private final ShiftWindowRule rule = new ShiftWindowRule("shift", 25, 30);

        @Test
        void 복귀까지_여유가_있으면_통과한다() {
            VehicleSpec vehicle = RuleFixtures.vehicleWithShiftEnd(START.plus(Duration.ofHours(8)));

            assertThat(rule.check(RuleFixtures.stop(GANGNAM), vehicle,
                    RuleFixtures.emptyRoute(vehicle)).feasible()).isTrue();
        }

        @Test
        void 판정_기준은_도착이_아니라_캠프_복귀다() {
            // 강남 도착까지는 되지만 시청으로 돌아올 시간이 없는 근무창을 만든다.
            VehicleSpec vehicle = RuleFixtures.vehicleWithShiftEnd(START.plus(Duration.ofMinutes(60)));
            Stop stop = RuleFixtures.stop(GANGNAM);
            RouteState state = RuleFixtures.emptyRoute(vehicle);

            assertThat(state.arrivalIfAppended(stop)).isBefore(vehicle.shift().end());
            assertThat(rule.check(stop, vehicle, state).feasible())
                    .as("복귀 + 버퍼 30분을 더하면 넘는다").isFalse();
        }

        @Test
        void 버퍼가_없으면_같은_라우트가_통과한다() {
            // 버퍼는 계획과 현실의 차이를 흡수한다. 0 이면 계획상 딱 맞는 라우트가 만들어진다.
            VehicleSpec vehicle = RuleFixtures.vehicleWithShiftEnd(START.plus(Duration.ofMinutes(60)));
            Stop stop = RuleFixtures.stop(GANGNAM);

            assertThat(new ShiftWindowRule("shift", 25, 0)
                    .check(stop, vehicle, RuleFixtures.emptyRoute(vehicle)).feasible()).isTrue();
        }

        @Test
        void 음수_버퍼는_거부한다() {
            assertThatThrownBy(() -> new ShiftWindowRule("shift", 25, -1))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
    class 지각_상한 {

        private final TimeWindowLimitRule rule = new TimeWindowLimitRule("late-limit", 30, 60);

        @Test
        void 창_안에_도착하면_통과한다() {
            VehicleSpec vehicle = RuleFixtures.vehicle();

            assertThat(rule.check(RuleFixtures.stop(GANGNAM), vehicle,
                    RuleFixtures.emptyRoute(vehicle)).feasible()).isTrue();
        }

        @Test
        void 상한_안의_지각은_통과한다() {
            // 소프트 페널티가 감당할 구간이다.
            VehicleSpec vehicle = RuleFixtures.vehicle();
            TimeWindow tight = new TimeWindow(START, START.plusSeconds(60));
            Stop stop = RuleFixtures.stopPromised(GANGNAM, tight);

            assertThat(rule.check(stop, vehicle, RuleFixtures.emptyRoute(vehicle)).feasible()).isTrue();
        }

        @Test
        void 상한을_넘는_지각은_배정하지_않는다() {
            // 소프트만 있으면 비용을 감수하고 얼마든지 늦출 수 있다. 이 룰이 바닥을 만든다.
            VehicleSpec vehicle = RuleFixtures.vehicle();
            TimeWindow past = new TimeWindow(START.minus(Duration.ofHours(3)),
                    START.minus(Duration.ofHours(2)));
            Stop stop = RuleFixtures.stopPromised(GANGNAM, past);

            Feasibility result = rule.check(stop, vehicle, RuleFixtures.emptyRoute(vehicle));

            assertThat(result.feasible()).isFalse();
            assertThat(result.reason()).contains("상한 60분");
        }

        @Test
        void 기준은_도착_시각이다() {
            VehicleSpec vehicle = RuleFixtures.vehicle();
            Stop stop = RuleFixtures.stopPromised(GANGNAM, PROMISED);
            RouteState state = RuleFixtures.emptyRoute(vehicle);

            assertThat(state.arrivalIfAppended(stop)).isBefore(PROMISED.end());
            assertThat(rule.check(stop, vehicle, state).feasible()).isTrue();
        }
    }

    @Test
    void 하드_룰_다섯_종이_모두_구현돼_있다() {
        Set<RuleType> hard = java.util.Arrays.stream(RuleType.values())
                .filter(type -> type.severity() == RuleSeverity.HARD)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(hard).containsExactlyInAnyOrder(RuleType.VEHICLE_ATTRIBUTE_MATCH,
                RuleType.VEHICLE_CAPACITY, RuleType.MAX_STOPS_PER_ROUTE, RuleType.SHIFT_WINDOW,
                RuleType.TIME_WINDOW_LIMIT);
    }
}
