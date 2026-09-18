package com.dawnline.dispatch.domain.optimizer.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.dispatch.domain.optimizer.DispatchRule;
import com.dawnline.dispatch.domain.optimizer.HardRule;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 룰이 <strong>자기 stop 상한을 스스로 말한다</strong> ([ADR-038]).
 *
 * <p>클러스터러와 §6.9 의 고정비 하한이 「라우트 하나에 몇 개까지인가」를 알아야 하는데,
 * 룰은 데이터라 파라미터를 읽을 수 없다(§6.3). 그래서 파라미터를 여는 대신 <strong>룰이
 * 답하는 질문을 하나 더</strong> 뒀다 — {@link HardRule#routeStopCap()}.
 *
 * <p>목록은 <strong>빼는 방식</strong>으로 적는다(CLAUDE.md). 드는 방식이면 새 룰이 조용히
 * 검사 밖에 남고, 「답해야 하는데 안 답하는 룰」이 생겨도 아무 데도 나타나지 않는다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("stop 상한 — 룰이 답하는 두 번째 질문")
class RouteStopCapTest {

    @ParameterizedTest
    @EnumSource(value = RuleType.class, mode = EnumSource.Mode.EXCLUDE,
            names = "MAX_STOPS_PER_ROUTE")
    void 나머지_아홉_타입은_stop_상한을_말하지_않는다(RuleType type) {
        DispatchRule rule = DispatchRules.of(RuleFixtures.definitionFor(type));

        if (rule instanceof HardRule hard) {
            assertThat(hard.routeStopCap())
                    .as("%s 는 자기 판정을 stop 수 상한 하나로 <남김없이> 요약할 수 없다. "
                            + "요약할 수 있게 되는 날 답해야 하는 것은 이 테스트가 아니라 그 룰이다",
                            type)
                    .isEmpty();
            return;
        }
        assertThat(rule)
                .as("%s 는 하드 룰이 아니라 질문 자체가 없다 — 소프트 룰은 배정을 막지 않는다", type)
                .isNotInstanceOf(HardRule.class);
    }

    @Test
    void 제외한_하나는_자기_파라미터를_답한다() {
        // 위 테스트가 「언제나 비어 있다」로도 통과하지 않게 하는 짝이다.
        HardRule rule = (HardRule) DispatchRules.of(
                RuleFixtures.definitionFor(RuleType.MAX_STOPS_PER_ROUTE));

        assertThat(rule.routeStopCap()).hasValue(120);
    }

    @Test
    void 묶음은_답하는_룰들의_최솟값을_쓴다() {
        // 상한은 <동시에> 성립해야 하므로 가장 작은 것이 실제로 무는 값이다.
        RuleSet rules = DispatchRules.ruleSet(List.of(
                RuleFixtures.definition("max-stops", RuleType.MAX_STOPS_PER_ROUTE, 20,
                        Map.of("max", 120)),
                RuleFixtures.definition("camp-override", RuleType.MAX_STOPS_PER_ROUTE, 21,
                        Map.of("max", 80)),
                RuleFixtures.definitionFor(RuleType.VEHICLE_CAPACITY)), 1);

        assertThat(rules.routeStopCap()).hasValue(80);
    }

    @Test
    void 아무도_답하지_않으면_상한이_없다() {
        // 「무한」으로 접지 않는다 — 상한이 없는 것과 0 인 것은 다른 말이고, 호출부가 그
        // 차이를 보고 결정한다(고정비 하한은 stop 축을 아예 재지 않는다).
        RuleSet rules = DispatchRules.ruleSet(
                List.of(RuleFixtures.definitionFor(RuleType.VEHICLE_CAPACITY)), 1);

        assertThat(rules.routeStopCap()).isEmpty();
        assertThat(RuleSet.empty().routeStopCap()).isEmpty();
    }
}
