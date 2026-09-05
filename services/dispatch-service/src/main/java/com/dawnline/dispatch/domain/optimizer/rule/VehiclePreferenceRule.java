package com.dawnline.dispatch.domain.optimizer.rule;

import com.dawnline.common.Money;
import com.dawnline.common.error.ValidationException;
import com.dawnline.dispatch.domain.optimizer.RouteState;
import com.dawnline.dispatch.domain.optimizer.SoftRule;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.util.List;
import java.util.Set;

/**
 * 선호하지 않는 차종에 배정하면 페널티 (§6.3 {@code VEHICLE_PREFERENCE}, SOFT).
 *
 * <p>"소형 물량에 대형 차량" 같은 낭비를 비용으로 표현한다. 하드가 아닌 이유는 <strong>차가 그것밖에
 * 없을 때는 그래도 배송해야 하기</strong> 때문이다 — 페널티는 대안이 있을 때만 선택을 바꾼다.
 *
 * <p>페널티는 라우트에 <strong>처음 붙을 때 한 번</strong>이다. stop 마다 물리면 같은 차로 많이
 * 배송할수록 벌을 받아, 차를 나눠 쓰는 쪽이 싸 보인다 — 이 룰이 막으려던 것과 반대다.
 */
public record VehiclePreferenceRule(String name, int priority, Set<String> preferredTypes,
        long penaltyKrw) implements SoftRule {

    public VehiclePreferenceRule {
        preferredTypes = Set.copyOf(preferredTypes);
        if (preferredTypes.isEmpty()) {
            throw ValidationException.field(name + ".params.preferredTypes", preferredTypes,
                    "선호 차종은 하나 이상이어야 합니다");
        }
        if (penaltyKrw < 0L) {
            throw ValidationException.field(name + ".params.penaltyKrw", penaltyKrw,
                    "페널티는 음수일 수 없습니다");
        }
    }

    static VehiclePreferenceRule of(RuleDefinition definition) {
        RuleParams params = new RuleParams(definition.name(), definition.params());
        List<String> types = params.requireStrings("preferredTypes");
        return new VehiclePreferenceRule(definition.name(), definition.priority(),
                Set.copyOf(types), params.requireLong("penaltyKrw"));
    }

    @Override
    public Money penalty(Stop stop, VehicleSpec vehicle, RouteState state) {
        if (preferredTypes.contains(vehicle.attrs().type())) {
            return Money.ZERO;
        }
        return state.isEmpty() ? Money.krw(penaltyKrw) : Money.ZERO;
    }
}
