package com.dawnline.dispatch.domain.optimizer.rule;

import com.dawnline.dispatch.domain.optimizer.Feasibility;
import com.dawnline.dispatch.domain.optimizer.HardRule;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.RouteState;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;

/**
 * 누적 적재가 용량을 넘지 않게 한다 (§6.3 {@code VEHICLE_CAPACITY}, HARD).
 *
 * <p>파라미터가 없다 — 용량은 차량 스펙에 있다. 그래도 룰인 이유는 <strong>끌 수 있어야 하기</strong>
 * 때문이 아니라(끄면 안 된다) 위반 사유가 다른 룰들과 같은 경로로 나와야 하기 때문이다.
 */
public record VehicleCapacityRule(String name, int priority) implements HardRule {

    static VehicleCapacityRule of(RuleDefinition definition) {
        return new VehicleCapacityRule(definition.name(), definition.priority());
    }

    @Override
    public Feasibility check(Stop stop, VehicleSpec vehicle, RouteState state) {
        Parcel after = state.load().plus(stop.parcel());
        if (vehicle.capacity().admits(after)) {
            return Feasibility.ok();
        }
        return Feasibility.violated(name, "용량 초과: %dg/%dcm3 → 한도 %dg/%dcm3".formatted(
                after.weightG(), after.volumeCm3(),
                vehicle.capacity().maxWeightG(), vehicle.capacity().maxVolumeCm3()));
    }
}
