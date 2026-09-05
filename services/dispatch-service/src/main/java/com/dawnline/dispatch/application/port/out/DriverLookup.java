package com.dawnline.dispatch.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * 차량에 배정된 기사 (DESIGN.md §5.3 {@code drivers.vehicle_id}).
 *
 * <p>{@code route.assigned.driverId} 에 쓴다. 최적화는 기사를 모른다 — 차량의 용량·속성·근무창만
 * 보고(§6.2), 그 차를 누가 모는지는 발행 시점의 사실이다.
 */
@FunctionalInterface
public interface DriverLookup {

    /**
     * @param vehicleId 차량 id
     */
    Optional<UUID> driverOf(UUID vehicleId);
}
