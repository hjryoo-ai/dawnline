package com.dawnline.dispatch.domain.optimizer.rule;

import com.dawnline.dispatch.domain.optimizer.Feasibility;
import com.dawnline.dispatch.domain.optimizer.HardRule;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.RouteState;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleAttrs;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import com.dawnline.common.error.ValidationException;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 주문 속성과 차량 속성을 맞춘다 (§6.3 {@code VEHICLE_ATTRIBUTE_MATCH}, HARD).
 *
 * <p>파라미터가 {@code orderFlag}/{@code vehicleFlag} <em>문자열</em>인 이유는 룰이 데이터이기
 * 때문이다 — "냉장" 과 "위험물" 이라는 두 규칙을 코드 두 벌이 아니라 정의 두 줄로 표현한다.
 * 대신 알 수 없는 플래그 이름은 <strong>룰을 만드는 시점에</strong> 거부한다.
 *
 * <p>{@code hazmat} 의 방향에 주의: 위험물 주문은 <em>허용된</em> 차량에만 실린다. 냉장은
 * "필요하면 냉장차" 이고 위험물은 "위험물이면 허용차" 라 둘 다 "주문이 참이면 차량도 참" 으로 같다.
 */
public record VehicleAttributeMatchRule(String name, int priority, String orderFlag,
        String vehicleFlag) implements HardRule {

    private static final Map<String, Predicate<Parcel>> ORDER_FLAGS = Map.of(
            "requiresCold", Parcel::requiresCold,
            "hazmat", Parcel::hazmat);

    private static final Map<String, Predicate<VehicleAttrs>> VEHICLE_FLAGS = Map.of(
            "isCold", VehicleAttrs::cold,
            "allowsHazmat", VehicleAttrs::allowsHazmat);

    public VehicleAttributeMatchRule {
        if (!ORDER_FLAGS.containsKey(orderFlag)) {
            throw ValidationException.field(name + ".params.orderFlag", orderFlag,
                    "알 수 없는 주문 플래그입니다. 가능한 값: " + ORDER_FLAGS.keySet());
        }
        if (!VEHICLE_FLAGS.containsKey(vehicleFlag)) {
            throw ValidationException.field(name + ".params.vehicleFlag", vehicleFlag,
                    "알 수 없는 차량 플래그입니다. 가능한 값: " + VEHICLE_FLAGS.keySet());
        }
    }

    static VehicleAttributeMatchRule of(RuleDefinition definition) {
        RuleParams params = new RuleParams(definition.name(), definition.params());
        return new VehicleAttributeMatchRule(definition.name(), definition.priority(),
                params.requireString("orderFlag"), params.requireString("vehicleFlag"));
    }

    @Override
    public Feasibility check(Stop stop, VehicleSpec vehicle, RouteState state) {
        boolean orderNeeds = ORDER_FLAGS.get(orderFlag).test(stop.parcel());
        if (!orderNeeds) {
            return Feasibility.ok();
        }
        return VEHICLE_FLAGS.get(vehicleFlag).test(vehicle.attrs())
                ? Feasibility.ok()
                : Feasibility.violated(name,
                        "%s 인 주문에 %s 가 아닌 차량입니다".formatted(orderFlag, vehicleFlag));
    }
}
