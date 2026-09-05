package com.dawnline.fulfillment.adapter.out.messaging;

import com.dawnline.common.TimeWindow;
import com.dawnline.messaging.outbox.OutboxAppender;
import com.dawnline.messaging.outbox.OutboxMessage;
import com.dawnline.fulfillment.application.port.in.PlacedOrderSnapshot;
import com.dawnline.fulfillment.application.port.out.FulfillmentEvents;
import com.dawnline.fulfillment.domain.UnserviceableReason;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link FulfillmentEvents} 의 outbox 구현 (불변규칙 1, §4.4).
 *
 * <p>{@code append} 는 {@code outbox_events} 에 행 하나를 INSERT 할 뿐이고 그 INSERT 는 호출한
 * 유스케이스의 트랜잭션에 참여한다 — 판정이 롤백되면 이벤트도 사라진다. 브로커로 실제로 보내는
 * 것은 릴레이의 일이다.
 *
 * <p>파티션 키는 {@code orderId} 다(§4.1). 같은 주문의 이벤트가 같은 파티션으로 가야
 * order-service 가 보는 순서가 유지된다.
 */
public class OutboxFulfillmentEvents implements FulfillmentEvents {

    private final OutboxAppender outbox;

    /**
     * @param outbox 이벤트 발행의 유일한 진입점
     */
    public OutboxFulfillmentEvents(OutboxAppender outbox) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
    }

    @Override
    public void planned(PlacedOrderSnapshot snapshot, UUID fcId, UUID campId, UUID zoneId, UUID waveId,
            Instant waveCutoffAt, TimeWindow window, boolean revised) {

        append(snapshot, FulfillmentPlannedPayload.planned(
                snapshot, fcId, campId, zoneId, waveId, waveCutoffAt, window, revised));
    }

    @Override
    public void unserviceable(PlacedOrderSnapshot snapshot, UnserviceableReason reason) {
        append(snapshot, FulfillmentPlannedPayload.unserviceable(snapshot, reason));
    }

    private void append(PlacedOrderSnapshot snapshot, FulfillmentPlannedPayload payload) {
        outbox.append(OutboxMessage.of(
                FulfillmentPlannedPayload.AGGREGATE_TYPE,
                snapshot.orderId(),
                FulfillmentPlannedPayload.EVENT_TYPE,
                FulfillmentPlannedPayload.SCHEMA_VERSION,
                snapshot.orderId().toString(),
                payload));
    }
}
