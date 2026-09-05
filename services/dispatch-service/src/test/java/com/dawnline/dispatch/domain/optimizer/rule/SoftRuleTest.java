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

        private final PriorityBoostRule rule = new PriorityBoostRule("priority", 120, 3_000);

        @Test
        void 우선도가_0_이면_보너스가_없다() {
            VehicleSpec vehicle = RuleFixtures.vehicle();

            assertThat(rule.penalty(RuleFixtures.stop(GANGNAM), vehicle,
                    RuleFixtures.emptyRoute(vehicle))).isEqualTo(Money.ZERO);
        }

        @Test
        void 보너스는_음수_페널티다() {
            VehicleSpec vehicle = RuleFixtures.vehicle();
            Stop vip = RuleFixtures.stop(GANGNAM, Parcel.EMPTY, 2, 1);

            assertThat(rule.penalty(vip, vehicle, RuleFixtures.emptyRoute(vehicle)))
                    .isEqualTo(Money.krw(-6_000));
        }

        @Test
        void 뒤로_갈수록_보너스가_줄어든다() {
            // 상수 보너스는 총비용에서 순서를 구별하지 못한다 — "앞 순서에 두면" 이 값에 들어가려면
            // 순번이 식에 있어야 한다.
            VehicleSpec vehicle = RuleFixtures.vehicle();
            Stop vip = RuleFixtures.stop(GANGNAM, Parcel.EMPTY, 1, 1);
            RouteState first = RuleFixtures.emptyRoute(vehicle);
            RouteState second = first.append(RuleFixtures.stop(CITY_HALL));
            RouteState fourth = second.append(RuleFixtures.stop(YEOUIDO))
                    .append(RuleFixtures.stop(CITY_HALL));

            assertThat(rule.penalty(vip, vehicle, first)).isEqualTo(Money.krw(-3_000));
            assertThat(rule.penalty(vip, vehicle, second)).isEqualTo(Money.krw(-1_500));
            assertThat(rule.penalty(vip, vehicle, fourth)).isEqualTo(Money.krw(-750));
        }

        @Test
        void 음수_보너스는_거부한다() {
            // 부호는 이 룰이 붙인다. 정의가 음수를 적으면 보너스가 페널티가 된다.
            assertThatThrownBy(() -> new PriorityBoostRule("p", 120, -1))
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
