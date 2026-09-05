package com.dawnline.dispatch.domain.optimizer.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.error.ValidationException;
import com.dawnline.dispatch.domain.optimizer.DispatchRule;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 정의(데이터) → 평가기(코드) 이음매. 잘못된 정의는 계획 도중이 아니라 여기서 터져야 한다. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DispatchRulesTest {

    @Test
    void 열_가지_타입이_모두_평가기로_만들어진다() {
        // switch 가 enum 을 전부 다루는지는 컴파일러가 보지만, 각 분기가 실제로 동작하는지는
        // 파라미터가 맞아야 알 수 있다.
        for (RuleType type : RuleType.values()) {
            DispatchRule rule = DispatchRules.of(definitionFor(type));
            assertThat(rule.name()).isEqualTo(type.name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    @Test
    void 심각도가_타입과_어긋나면_거부한다() {
        // 정의가 자기 자신과 어긋났다. "HARD 로 적었는데 왜 배정됐지" 를 코드에서 찾게 두지 않는다.
        assertThatThrownBy(() -> new RuleDefinition("x", RuleType.TIME_WINDOW_PENALTY,
                RuleSeverity.HARD, 100, Map.of("penaltyPerMinuteKrw", 50)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("SOFT");
    }

    @Test
    void 필수_파라미터가_없으면_룰_이름과_함께_실패한다() {
        RuleDefinition broken =
                RuleFixtures.definition("max-stops", RuleType.MAX_STOPS_PER_ROUTE, 20, Map.of());

        assertThatThrownBy(() -> DispatchRules.of(broken))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("max-stops.params.max");
    }

    @Test
    void 파라미터_타입이_다르면_거부한다() {
        RuleDefinition broken = RuleFixtures.definition("max-stops", RuleType.MAX_STOPS_PER_ROUTE, 20,
                Map.of("max", "백이십"));

        assertThatThrownBy(() -> DispatchRules.of(broken))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("숫자");
    }

    @Test
    void 이름이_겹치면_거부한다() {
        // Explanation.ruleName 이 이름이라, 같은 이름이 둘이면 설명을 보고 어느 룰인지 알 수 없다.
        List<RuleDefinition> duplicated = List.of(
                RuleFixtures.definition("same", RuleType.MAX_STOPS_PER_ROUTE, 20, Map.of("max", 10)),
                RuleFixtures.definition("same", RuleType.VEHICLE_CAPACITY, 15, Map.of()));

        assertThatThrownBy(() -> DispatchRules.ruleSet(duplicated, 1))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("same");
    }

    @Test
    void 묶음은_심각도별로_갈리고_버전을_들고_있다() {
        RuleSet rules = DispatchRules.ruleSet(List.of(
                definitionFor(RuleType.VEHICLE_CAPACITY),
                definitionFor(RuleType.TIME_WINDOW_PENALTY),
                definitionFor(RuleType.UNASSIGNED_PENALTY)), 7);

        assertThat(rules.hardRules()).hasSize(1);
        assertThat(rules.softRules()).hasSize(1);
        assertThat(rules.unassignedRules()).hasSize(1);
        assertThat(rules.version()).isEqualTo(7);
    }

    private static RuleDefinition definitionFor(RuleType type) {
        String name = type.name().toLowerCase(java.util.Locale.ROOT);
        Map<String, Object> params = switch (type) {
            case VEHICLE_ATTRIBUTE_MATCH ->
                    Map.of("orderFlag", "requiresCold", "vehicleFlag", "isCold");
            case VEHICLE_CAPACITY -> Map.of();
            case MAX_STOPS_PER_ROUTE -> Map.of("max", 120);
            case SHIFT_WINDOW -> Map.of("bufferMinutes", 30);
            case TIME_WINDOW_LIMIT -> Map.of("hardLimitMinutes", 60);
            case TIME_WINDOW_PENALTY -> Map.of("penaltyPerMinuteKrw", 50);
            case ZONE_AFFINITY -> Map.of("crossZonePenaltyKrw", 2_000);
            case PRIORITY_BOOST -> Map.of("bonusKrw", 3_000);
            case VEHICLE_PREFERENCE ->
                    Map.of("preferredTypes", List.of("VAN"), "penaltyKrw", 4_000);
            case UNASSIGNED_PENALTY -> Map.of("baseKrw", 30_000, "perPriorityKrw", 20_000);
        };
        return RuleFixtures.definition(name, type, 10, params);
    }
}
