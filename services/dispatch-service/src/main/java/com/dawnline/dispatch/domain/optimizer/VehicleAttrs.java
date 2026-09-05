package com.dawnline.dispatch.domain.optimizer;

import java.util.Objects;

/**
 * 차량의 속성 (DESIGN.md §6.2).
 *
 * @param type          차종. {@code VEHICLE_PREFERENCE} 소프트 룰이 본다
 * @param cold          냉장 차량인가
 * @param allowsHazmat  위험물 적재가 허용되는가
 */
public record VehicleAttrs(String type, boolean cold, boolean allowsHazmat) {

    public VehicleAttrs {
        Objects.requireNonNull(type, "type");
    }
}
