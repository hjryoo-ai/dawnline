package com.dawnline.dispatch.domain.optimizer.rule;

import com.dawnline.dispatch.domain.optimizer.DispatchRule;
import com.dawnline.dispatch.domain.optimizer.RuleSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 정의(데이터)를 평가기(코드)로 바꾼다 (DESIGN.md §6.3).
 *
 * <p>여기가 "룰은 데이터, 평가기는 코드" 의 이음매다. 알 수 없는 타입이나 잘못된 파라미터는
 * <strong>계획 도중이 아니라 여기서</strong> 실패한다 — 계획이 절반쯤 돈 뒤에 터지면 어느 룰이
 * 문제인지 스택트레이스에서 찾아야 한다.
 */
public final class DispatchRules {

    private DispatchRules() {
    }

    /**
     * 정의 하나를 평가기로.
     *
     * @param definition 룰 정의
     */
    public static DispatchRule of(RuleDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        return switch (definition.type()) {
            case VEHICLE_ATTRIBUTE_MATCH -> VehicleAttributeMatchRule.of(definition);
            case VEHICLE_CAPACITY -> VehicleCapacityRule.of(definition);
            case MAX_STOPS_PER_ROUTE -> MaxStopsPerRouteRule.of(definition);
            case SHIFT_WINDOW -> ShiftWindowRule.of(definition);
            case TIME_WINDOW_LIMIT -> TimeWindowLimitRule.of(definition);
            case TIME_WINDOW_PENALTY -> TimeWindowPenaltyRule.of(definition);
            case ZONE_AFFINITY -> ZoneAffinityRule.of(definition);
            case PRIORITY_BOOST -> PriorityBoostRule.of(definition);
            case VEHICLE_PREFERENCE -> VehiclePreferenceRule.of(definition);
            case UNASSIGNED_PENALTY -> UnassignedPenaltyRule.of(definition);
        };
    }

    /**
     * 정의 목록을 룰 묶음으로.
     *
     * <p>이름이 겹치면 거부한다 — {@code Explanation.ruleName} 이 이름이라, 같은 이름이 둘이면
     * 설명을 보고 어느 룰인지 알 수 없다.
     *
     * @param definitions 룰 정의들
     * @param version     {@code dispatch_rules.rule_version}
     */
    public static RuleSet ruleSet(List<RuleDefinition> definitions, int version) {
        Objects.requireNonNull(definitions, "definitions");
        Map<String, RuleDefinition> byName = new LinkedHashMap<>();
        for (RuleDefinition definition : definitions) {
            RuleDefinition previous = byName.put(definition.name(), definition);
            if (previous != null) {
                throw new com.dawnline.common.error.ValidationException(
                        "룰 이름이 겹칩니다: " + definition.name(),
                        Map.of("name", definition.name()));
            }
        }
        return RuleSet.of(byName.values().stream().map(DispatchRules::of).toList(), version);
    }
}
