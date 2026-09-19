package com.dawnline.tracking.application.port.out;

import com.dawnline.tracking.domain.ShipmentEvent;
import java.util.Collection;

/**
 * {@code shipment_events} 적재 (DESIGN.md §5.4).
 *
 * <p>추가만 있고 갱신도 삭제도 없다 — 일어난 일은 나중에 달라지지 않는다. 오래된 파티션을
 * 통째로 떼는 것이 유일한 삭제이고, 그것은 {@link EventPartitions} 의 몫이다.
 */
public interface ShipmentEvents {

    /**
     * 사건들을 적재한다. 한 스캔이 stop 의 주문 수만큼 행이 되므로 묶어서 받는다.
     *
     * @param events 적재할 사건들. 비어 있으면 아무것도 하지 않는다
     */
    void appendAll(Collection<ShipmentEvent> events);
}
