package com.dawnline.dispatch.domain.optimizer;

import java.util.Objects;
import java.util.UUID;

/**
 * 차량 식별자 (DESIGN.md §6.2). 감싸는 이유는 {@link OrderId} 와 같다.
 *
 * @param value UUIDv7 (불변규칙 10)
 */
public record VehicleId(UUID value) {

    public VehicleId {
        Objects.requireNonNull(value, "value");
    }

    /** 읽기 쉬운 별칭. */
    public static VehicleId of(UUID value) {
        return new VehicleId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
