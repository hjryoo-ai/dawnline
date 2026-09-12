package com.dawnline.dispatch.domain.optimizer.rule;

import static com.dawnline.dispatch.domain.optimizer.rule.RuleFixtures.CITY_HALL;
import static com.dawnline.dispatch.domain.optimizer.rule.RuleFixtures.GANGNAM;
import static com.dawnline.dispatch.domain.optimizer.rule.RuleFixtures.START;
import static com.dawnline.dispatch.domain.optimizer.rule.RuleFixtures.YEOUIDO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.Money;
import com.dawnline.common.TimeWindow;
import com.dawnline.common.error.ValidationException;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.RouteState;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleAttrs;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** §6.3 소프트 룰 5종 — 각각 붙는 경우와 붙지 않는 경우를 본다. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SoftRuleTest {

    @Nested
    @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
    class 지각_페널티 {

        private final TimeWindowPenaltyRule rule = new TimeWindowPenaltyRule("late", 100, 50);

        @Test
        void 창_안에_도착하면_0_원이다() {
            VehicleSpec vehicle = RuleFixtures.vehicle();

            assertThat(rule.penalty(RuleFixtures.stop(GANGNAM), vehicle,
                    RuleFixtures.emptyRoute(vehicle))).isEqualTo(Money.ZERO);
        }

        @Test
        void 초과_분에_비례한다() {
            VehicleSpec vehicle = RuleFixtures.vehicle();
            TimeWindow past = new TimeWindow(START.minus(Duration.ofHours(2)),
                    START.minus(Duration.ofHours(1)));
            Stop stop = RuleFixtures.stopPromised(GANGNAM, past);
            RouteState state = RuleFixtures.emptyRoute(vehicle);

            long late = Duration.between(past.end(), state.arrivalIfAppended(stop)).toMinutes();

            assertThat(rule.penalty(stop, vehicle, state)).isEqualTo(Money.krw(50L * late));
        }

        @Test
        void 음수_페널티는_거부한다() {
            assertThatThrownBy(() -> new TimeWindowPenaltyRule("late", 100, -1))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
    class 권역_친화 {

        private final ZoneAffinityRule rule = new ZoneAffinityRule("zone", 110, 2_000);

        @Test
        void 첫_stop_은_권역을_넘는_것이_아니다() {
            VehicleSpec vehicle = RuleFixtures.vehicle();

            assertThat(rule.penalty(RuleFixtures.stop(GANGNAM), vehicle,
                    RuleFixtures.emptyRoute(vehicle))).isEqualTo(Money.ZERO);
        }

        @Test
        void 같은_권역의_두_번째_stop_에는_붙지_않는다() {
            // 붙이면 권역 안을 도는 것이 벌 받는다.
            VehicleSpec vehicle = RuleFixtures.vehicle();
            RouteState state = RuleFixtures.emptyRoute(vehicle).append(RuleFixtures.stop(GANGNAM));

            assertThat(rule.penalty(RuleFixtures.stop(GANGNAM), vehicle, state)).isEqualTo(Money.ZERO);
        }

        @Test
        void 새_권역이_늘면_한_번_붙는다() {
            VehicleSpec vehicle = RuleFixtures.vehicle();
            RouteState state = RuleFixtures.emptyRoute(vehicle).append(RuleFixtures.stop(GANGNAM));

            assertThat(rule.penalty(RuleFixtures.stop(YEOUIDO), vehicle, state))
                    .isEqualTo(Money.krw(2_000));
        }

        @Test
        void 돌아온_권역에는_다시_붙지_않는다() {
            VehicleSpec vehicle = RuleFixtures.vehicle();
            RouteState state = RuleFixtures.emptyRoute(vehicle)
                    .append(RuleFixtures.stop(GANGNAM))
                    .append(RuleFixtures.stop(YEOUIDO));

            assertThat(rule.penalty(RuleFixtures.stop(GANGNAM), vehicle, state)).isEqualTo(Money.ZERO);
        }
    }

    @Nested
    @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
    class 우선도_보너스 {

        private static final long HALF_LIFE = 12L;
        private final PriorityBoostRule rule =
                new PriorityBoostRule("priority", 120, 3_000, HALF_LIFE);

        @Test
        void 우선도가_0_이면_보너스가_없다() {
            VehicleSpec vehicle = RuleFixtures.vehicle();

            assertThat(rule.penalty(RuleFixtures.stop(GANGNAM), vehicle,
                    RuleFixtures.emptyRoute(vehicle))).isEqualTo(Money.ZERO);
        }

        @Test
        void 보너스는_음수_페널티이고_우선도에_비례한다() {
            VehicleSpec vehicle = RuleFixtures.vehicle();
            RouteState state = RuleFixtures.emptyRoute(vehicle);
            Stop one = RuleFixtures.stop(GANGNAM, Parcel.EMPTY, 1, 1);
            Stop two = RuleFixtures.stop(GANGNAM, Parcel.EMPTY, 2, 1);

            Money single = rule.penalty(one, vehicle, state);
            assertThat(single.krw()).isNegative();
            assertThat(rule.penalty(two, vehicle, state).krw())
                    .as("같은 자리·같은 시각이면 우선도만 값을 가른다")
                    .isEqualTo(single.krw() * 2);
        }

        @Test
        void 반감기에서_절반이다() {
            // τ 의 뜻이 이름 그대로인지 — t = τ 에서 정확히 절반이다.
            VehicleSpec vehicle = RuleFixtures.vehicle();
            RouteState state = RuleFixtures.emptyRoute(vehicle);
            Stop vip = RuleFixtures.stop(GANGNAM, Parcel.EMPTY, 1, 1);
            long elapsed = Duration.between(START, state.arrivalIfAppended(vip)).toMinutes();
            assertThat(elapsed).as("도착이 계획 시작과 같으면 감쇠를 볼 수 없다").isPositive();

            PriorityBoostRule atHalfLife = new PriorityBoostRule("priority", 120, 3_000, elapsed);

            assertThat(atHalfLife.penalty(vip, vehicle, state)).isEqualTo(Money.krw(-1_500));
        }

        @Test
        void 늦게_도착할수록_보너스가_줄어든다() {
            VehicleSpec vehicle = RuleFixtures.vehicle();
            Stop vip = RuleFixtures.stop(GANGNAM, Parcel.EMPTY, 1, 1);
            RouteState early = RuleFixtures.emptyRoute(vehicle);
            RouteState late = early.append(RuleFixtures.stop(YEOUIDO))
                    .append(RuleFixtures.stop(CITY_HALL));

            assertThat(rule.penalty(vip, vehicle, late).krw())
                    .isGreaterThan(rule.penalty(vip, vehicle, early).krw());
        }

        @Test
        void 순번이_달라도_도착_시각이_같으면_같은_보너스다() {
            // 「앞 순서에」를 순번으로 재면 라우트를 쪼갤수록 보너스가 모인다 — 그것이
            // ÷ position 을 버린 이유다([ADR-040]). 시각으로 재면 순번은 값에 없다.
            VehicleSpec vehicle = RuleFixtures.vehicle();
            Stop vip = RuleFixtures.stop(GANGNAM, Parcel.EMPTY, 1, 1);
            RouteState first = RuleFixtures.emptyRoute(vehicle);
            RouteState fifth = first
                    .append(RuleFixtures.weightlessStop(CITY_HALL))
                    .append(RuleFixtures.weightlessStop(CITY_HALL))
                    .append(RuleFixtures.weightlessStop(CITY_HALL))
                    .append(RuleFixtures.weightlessStop(CITY_HALL));
            assertThat(fifth.time()).as("시각이 같아야 순번만 남는다").isEqualTo(first.time());
            assertThat(fifth.stopCount()).isEqualTo(4);

            assertThat(rule.penalty(vip, vehicle, fifth))
                    .isEqualTo(rule.penalty(vip, vehicle, first));
        }

        @Test
        void 기준은_라우트_출발이_아니라_계획_시작이다() {
            // 라우트 기준으로 재면 근무가 늦게 시작하는 차의 첫 자리가 만점이 되고, 「쪼개면
            // 첫 자리가 늘어난다」가 시각으로 되살아난다.
            VehicleSpec onTime = RuleFixtures.vehicle();
            VehicleSpec lateShift =
                    RuleFixtures.vehicleWithShiftStart(START.plus(Duration.ofHours(2)));
            Stop vip = RuleFixtures.stop(GANGNAM, Parcel.EMPTY, 1, 1);
            RouteState lateRoute = RuleFixtures.emptyRoute(lateShift);
            assertThat(lateRoute.startedAt()).as("근무창이 출발을 밀어야 두 기준이 갈린다")
                    .isAfter(lateRoute.planStartedAt());

            assertThat(rule.penalty(vip, lateShift, lateRoute).krw())
                    .isGreaterThan(rule.penalty(vip, onTime, RuleFixtures.emptyRoute(onTime)).krw());
        }

        @Test
        void 음수_보너스는_거부한다() {
            // 부호는 이 룰이 붙인다. 정의가 음수를 적으면 보너스가 페널티가 된다.
            assertThatThrownBy(() -> new PriorityBoostRule("p", 120, -1, HALF_LIFE))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void 반감기가_0_이하면_거부한다() {
            // 0 이면 보너스가 통째로 사라지고, 음수면 감쇠가 뒤집혀 늦을수록 상을 준다.
            assertThatThrownBy(() -> new PriorityBoostRule("p", 120, 3_000, 0))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> new PriorityBoostRule("p", 120, 3_000, -1))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
    class 차종_선호 {

        private final VehiclePreferenceRule rule =
                new VehiclePreferenceRule("preference", 130, Set.of("BIKE", "VAN"), 4_000);

        @Test
        void 선호_차종이면_0_원이다() {
            VehicleSpec van = RuleFixtures.vehicle(new VehicleAttrs("VAN", false, false));

            assertThat(rule.penalty(RuleFixtures.stop(GANGNAM), van, RuleFixtures.emptyRoute(van)))
                    .isEqualTo(Money.ZERO);
        }

        @Test
        void 비선호_차종에는_라우트당_한_번_붙는다() {
            // stop 마다 물리면 같은 차로 많이 배송할수록 벌을 받아, 차를 나눠 쓰는 쪽이 싸 보인다.
            VehicleSpec truck = RuleFixtures.vehicle(new VehicleAttrs("TRUCK", false, false));
            RouteState empty = RuleFixtures.emptyRoute(truck);
            RouteState loaded = empty.append(RuleFixtures.stop(GANGNAM));

            assertThat(rule.penalty(RuleFixtures.stop(GANGNAM), truck, empty))
                    .isEqualTo(Money.krw(4_000));
            assertThat(rule.penalty(RuleFixtures.stop(YEOUIDO), truck, loaded)).isEqualTo(Money.ZERO);
        }

        @Test
        void 선호_차종_목록이_비면_거부한다() {
            assertThatThrownBy(() -> new VehiclePreferenceRule("p", 130, Set.of(), 1))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
    class 미배정_비용 {

        private final UnassignedPenaltyRule rule =
                new UnassignedPenaltyRule("unassigned", 900, 30_000, 20_000);

        @Test
        void 기본_비용에_우선도_가산을_더한다() {
            assertThat(rule.penalty(RuleFixtures.stop(GANGNAM, Parcel.EMPTY, 2, 1)))
                    .isEqualTo(Money.krw(70_000));
        }

        @Test
        void 통합된_주문_수만큼_곱한다() {
            // stop 단위로 세면 3건짜리를 버리는 것이 1건짜리를 버리는 것과 같아진다.
            assertThat(rule.penalty(RuleFixtures.stop(GANGNAM, Parcel.EMPTY, 0, 3)))
                    .isEqualTo(Money.krw(90_000));
        }

        @Test
        void 차량도_라우트도_보지_않는다() {
            // 배정에 실패했으므로 볼 것이 없다 — 그래서 SoftRule 이 아니라 UnassignedRule 이다.
            assertThat(rule).isInstanceOf(com.dawnline.dispatch.domain.optimizer.UnassignedRule.class);
        }
    }
}
