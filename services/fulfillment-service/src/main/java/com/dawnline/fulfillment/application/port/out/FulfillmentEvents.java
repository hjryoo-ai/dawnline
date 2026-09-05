package com.dawnline.fulfillment.application.port.out;

import com.dawnline.common.TimeWindow;
import com.dawnline.fulfillment.application.port.in.PlacedOrderSnapshot;
import com.dawnline.fulfillment.domain.UnserviceableReason;
import java.time.Instant;
import java.util.UUID;

/**
 * fulfillment 가 발행하는 이벤트 (§4.1). 구현은 <strong>outbox</strong> 뿐이다(불변규칙 1) —
 * {@code KafkaTemplate} 을 유스케이스에서 직접 부르지 않는다.
 */
public interface FulfillmentEvents {

    /**
     * {@code fulfillment.planned} — 계획됨 (§4.3).
     *
     * @param snapshot     {@code order.placed} 스냅샷. 하류가 주문을 다시 묻지 않도록 되싣는다
     * @param fcId         선택된 FC
     * @param campId       캠프
     * @param zoneId       권역
     * @param waveId       편입된 웨이브
     * @param waveCutoffAt 그 웨이브의 컷오프. 개정됐다면 스냅샷의 값과 다르다
     * @param window       지금 유효한 약속창
     * @param revised      그 창이 접수 시점의 약속과 다른가 (ADR-020 결정 3)
     */
    void planned(PlacedOrderSnapshot snapshot, UUID fcId, UUID campId, UUID zoneId, UUID waveId,
            Instant waveCutoffAt, TimeWindow window, boolean revised);

    /**
     * {@code fulfillment.planned} — 배차 불가 (§4.3, §5.2 6단계).
     *
     * <p>같은 토픽·같은 키로 나간다. 배차하지 못한 것도 하류가 알아야 하는 사실이고,
     * order-service 는 이것을 받아 주문을 {@code FAILED} 로 둔다.
     *
     * @param snapshot 스냅샷
     * @param reason   사유
     */
    void unserviceable(PlacedOrderSnapshot snapshot, UnserviceableReason reason);
}
