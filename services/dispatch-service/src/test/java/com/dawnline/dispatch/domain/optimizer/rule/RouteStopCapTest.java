package com.dawnline.dispatch.domain.optimizer.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.common.error.ValidationException;
import com.dawnline.dispatch.domain.optimizer.Capacity;
import com.dawnline.dispatch.domain.optimizer.DispatchRule;
import com.dawnline.dispatch.domain.optimizer.HardRule;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import com.dawnline.dispatch.domain.optimizer.VehicleAttrs;
import com.dawnline.dispatch.domain.optimizer.VehicleCost;
import com.dawnline.dispatch.domain.optimizer.VehicleId;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.time.Duration;
import java.time.Instant;
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

    // ------------------------------------------------------------ 차량별 질문 — 근무창이 허락하는 stop 수 (ADR-039 후속 2)

    /** 두 번째 peak-day 의 새벽 계획 — 야간조 23:00–08:00 KST 에 23:58 계획. */
    private static final Instant NIGHT_START = Instant.parse("2026-09-26T14:00:00Z");
    private static final Instant PLANNED_AT = Instant.parse("2026-09-26T14:58:00Z");

    @ParameterizedTest
    @EnumSource(value = RuleType.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"MAX_STOPS_PER_ROUTE", "SHIFT_WINDOW"})
    void 나머지_여덟_타입은_차량별로도_stop_상한을_말하지_않는다(RuleType type) {
        DispatchRule rule = DispatchRules.of(RuleFixtures.definitionFor(type));

        if (rule instanceof HardRule hard) {
            assertThat(hard.routeStopCap(night(), PLANNED_AT)).as("%s", type).isEmpty();
        }
    }

    @Test
    void 제외한_max_stops_는_차량과_무관하게_자기_파라미터를_답한다() {
        HardRule rule = (HardRule) DispatchRules.of(RuleFixtures.definitionFor(RuleType.MAX_STOPS_PER_ROUTE));

        assertThat(rule.routeStopCap(night(), PLANNED_AT)).hasValue(120);
    }

    @Test
    void 제외한_근무창은_상수_셋이_있으면_근무창이_허락하는_stop_수를_답한다() {
        // (07:30 − 23:58 = 27,120초 − 왕복 827) ÷ (작업 158 + 이동 76) = 112.36 → 112. DESIGN §6.3 의 식과 시드 값 그대로다.
        HardRule rule = shiftWindow(Map.of("bufferMinutes", 30, "serviceSeconds", 158, "legSeconds", 76,
                "depotLegsSeconds", 827));

        assertThat(rule.routeStopCap(night(), PLANNED_AT)).hasValue(112);
        assertThat(rule.routeStopCap())
                .as("차량을 모르는 질문에는 답하지 않는다 — 함대 판정은 그 질문을 그대로 쓴다(ADR-067)").isEmpty();
    }

    @Test
    void 상수_셋이_없는_근무창은_판정만_한다() {
        // 기본값은 「모른다」다(§6.3) — 위 여덟 타입의 검사가 근무창을 뺀 이유가 이 테스트와 위 테스트 둘이다.
        HardRule rule = shiftWindow(Map.of("bufferMinutes", 30));

        assertThat(rule.routeStopCap(night(), PLANNED_AT)).isEmpty();
    }

    @Test
    void 출발은_계획_시작과_근무_시작_중_늦은_쪽이다() {
        HardRule rule = shiftWindow(Map.of("bufferMinutes", 30, "serviceSeconds", 158, "legSeconds", 76,
                "depotLegsSeconds", 827));

        assertThat(rule.routeStopCap(night(), NIGHT_START.minus(Duration.ofHours(1))))
                .as("근무 전에 계획되면 근무 시작부터 — (30,600 − 827) ÷ 234").hasValue(127);
        assertThat(rule.routeStopCap(night(), NIGHT_START.plus(Duration.ofHours(8)).plus(Duration.ofMinutes(20))))
                .as("버퍼 뺀 끝까지 10분 — 왕복도 못 한다. 음수가 아니라 0 이다").hasValue(0);
    }

    @Test
    void 상수_셋은_함께_있거나_함께_없다() {
        // 일부만 있으면 식이 설 수 없다 — 조용히 「모른다」로 두면 적어 둔 값이 아무 일도 하지 않는다.
        assertThatThrownBy(() -> shiftWindow(Map.of("bufferMinutes", 30, "serviceSeconds", 158)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("함께");
    }

    @Test
    void 묶음은_차량마다_답들의_최솟값을_쓴다() {
        RuleSet rules = DispatchRules.ruleSet(List.of(
                RuleFixtures.definition("max-stops", RuleType.MAX_STOPS_PER_ROUTE, 20, Map.of("max", 120)),
                RuleFixtures.definition("shift-window", RuleType.SHIFT_WINDOW, 25, Map.of("bufferMinutes", 30,
                        "serviceSeconds", 158, "legSeconds", 76, "depotLegsSeconds", 827))), 1);

        assertThat(rules.routeStopCap(night(), PLANNED_AT)).as("근무창이 문다").hasValue(112);
        assertThat(rules.routeStopCap(night(), NIGHT_START.minus(Duration.ofHours(1))))
                .as("근무가 넉넉하면 max-stops 가 문다 — min(127, 120)").hasValue(120);
        assertThat(rules.routeStopCap()).as("차량을 모르는 질문은 그대로다").hasValue(120);
    }

    private static HardRule shiftWindow(Map<String, Object> params) {
        return (HardRule) DispatchRules.of(RuleFixtures.definition("shift-window", RuleType.SHIFT_WINDOW, 25, params));
    }

    private static VehicleSpec night() {
        return new VehicleSpec(VehicleId.of(Ids.newId()), new Capacity(1_200_000, 6_000_000),
                new VehicleAttrs("TRUCK", true, false),
                new TimeWindow(NIGHT_START, NIGHT_START.plus(Duration.ofHours(9))),
                VehicleCost.krw(87_000, 900, 300));
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
