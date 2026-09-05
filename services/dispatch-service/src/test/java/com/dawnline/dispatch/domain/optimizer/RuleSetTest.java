package com.dawnline.dispatch.domain.optimizer;

import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.GANGNAM;
import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.START;
import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Money;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RuleSetTest {

    private final VehicleSpec vehicle = OptimizerFixtures.vehicle();
    private final Stop stop = OptimizerFixtures.stop(GANGNAM);
    private final RouteState state =
            RouteState.empty(OptimizerFixtures.vehicle(), OptimizerFixtures.depot(),
                    OptimizerFixtures.distance(), START);

    /** 호출 여부를 기록하는 하드 룰. */
    private record RecordingHard(String name, int priority, boolean pass, List<String> calls)
            implements HardRule {

        @Override
        public Feasibility check(Stop stop, VehicleSpec vehicle, RouteState state) {
            calls.add(name);
            return pass ? Feasibility.ok() : Feasibility.violated(name, name + " 위반");
        }
    }

    private record FixedSoft(String name, int priority, long krw) implements SoftRule {

        @Override
        public Money penalty(Stop stop, VehicleSpec vehicle, RouteState state) {
            return Money.krw(krw);
        }
    }

    @Test
    void 하드_룰은_우선순위_순서로_평가된다() {
        List<String> calls = new ArrayList<>();
        RuleSet rules = RuleSet.of(List.of(
                new RecordingHard("late", 30, true, calls),
                new RecordingHard("cold", 10, true, calls),
                new RecordingHard("stops", 20, true, calls)), 1);

        rules.check(stop, vehicle, state);

        assertThat(calls).containsExactly("cold", "stops", "late");
    }

    @Test
    void 하드_룰은_첫_위반에서_멈춘다() {
        // "여덟 개 다 어겼다" 는 운영자에게 답이 아니다.
        List<String> calls = new ArrayList<>();
        RuleSet rules = RuleSet.of(List.of(
                new RecordingHard("cold", 10, false, calls),
                new RecordingHard("stops", 20, true, calls)), 1);

        Feasibility result = rules.check(stop, vehicle, state);

        assertThat(result.feasible()).isFalse();
        assertThat(result.ruleName()).isEqualTo("cold");
        assertThat(calls).containsExactly("cold");
    }

    @Test
    void 우선순위가_같으면_이름으로_정렬해_결정적이다() {
        // seed 가 같으면 결과가 같아야 한다 (불변규칙 12). 룰 순서가 흔들리면 사유가 흔들린다.
        List<String> calls = new ArrayList<>();
        RuleSet rules = RuleSet.of(List.of(
                new RecordingHard("b", 10, true, calls),
                new RecordingHard("a", 10, true, calls)), 1);

        rules.check(stop, vehicle, state);

        assertThat(calls).containsExactly("a", "b");
    }

    @Test
    void 소프트_룰은_모두_평가해_합산한다() {
        RuleSet rules = RuleSet.of(List.of(
                new FixedSoft("late", 100, 3_000),
                new FixedSoft("zone", 110, 2_000)), 1);

        assertThat(rules.penalty(stop, vehicle, state)).isEqualTo(Money.krw(5_000));
    }

    @Test
    void 보너스는_음수로_합산된다() {
        // PRIORITY_BOOST 는 음의 페널티다.
        RuleSet rules = RuleSet.of(List.of(
                new FixedSoft("late", 100, 3_000),
                new FixedSoft("priority", 90, -5_000)), 1);

        assertThat(rules.penalty(stop, vehicle, state)).isEqualTo(Money.krw(-2_000));
    }

    @Test
    void 빈_룰셋은_통과시키고_0_원을_낸다() {
        RuleSet rules = RuleSet.empty();

        assertThat(rules.check(stop, vehicle, state).feasible()).isTrue();
        assertThat(rules.penalty(stop, vehicle, state)).isEqualTo(Money.ZERO);
        assertThat(rules.version()).isZero();
    }

    @Test
    void 하드와_소프트를_섞어_주면_각자의_목록으로_갈린다() {
        RuleSet rules = RuleSet.of(List.of(
                new RecordingHard("cold", 10, true, new ArrayList<>()),
                new FixedSoft("late", 100, 1_000)), 7);

        assertThat(rules.hardRules()).hasSize(1);
        assertThat(rules.softRules()).hasSize(1);
        assertThat(rules.version()).isEqualTo(7);
    }
}
