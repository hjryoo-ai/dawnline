package com.dawnline.tracking.adapter.out.messaging;

import com.dawnline.messaging.outbox.OutboxAppender;
import com.dawnline.messaging.outbox.OutboxMessage;
import com.dawnline.tracking.application.port.out.DeliveryEvents;
import com.dawnline.tracking.domain.ScanType;
import com.dawnline.tracking.domain.Shipment;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@link DeliveryEvents} 의 outbox 구현 (불변규칙 1, §4.4).
 *
 * <p>{@code append} 는 {@code outbox_events} 에 행을 INSERT 할 뿐이고, 그 INSERT 는 스캔
 * 유스케이스의 트랜잭션에 참여한다 — 상태 전이가 롤백되면 이벤트도 사라진다. 「완료로 바뀌었는데
 * 이벤트는 안 나간」 배송도, 「이벤트는 나갔는데 상태는 그대로인」 배송도 생기지 않는다.
 */
public class OutboxDeliveryEvents implements DeliveryEvents {

    private final OutboxAppender outbox;

    /**
     * @param outbox 이벤트 발행의 유일한 진입점
     */
    public OutboxDeliveryEvents(OutboxAppender outbox) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
    }

    @Override
    public void deliveryStatus(UUID routeId, int stopSeq, List<UUID> orderIds, ScanType type,
            Instant occurredAt, @Nullable String failureReason) {

        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(orderIds, "orderIds");
        if (orderIds.isEmpty()) {
            // 계약이 minItems 1 이다. 그리고 옮겨진 주문이 없으면 소비자에게 말할 새 사실이 없다.
            throw new IllegalArgumentException("주문 없는 delivery.status 는 발행하지 않습니다");
        }
        outbox.append(OutboxMessage.of(
                DeliveryStatusPayload.AGGREGATE_TYPE,
                routeId,
                DeliveryStatusPayload.EVENT_TYPE,
                DeliveryStatusPayload.SCHEMA_VERSION,
                routeId.toString(),
                DeliveryStatusPayload.of(routeId, stopSeq, orderIds, type, occurredAt,
                        failureReason)));
    }

    @Override
    public void deliveryAtRisk(UUID routeId, UUID campId, Instant detectedAt, Duration deviation,
            List<Shipment> remaining, Duration margin) {

        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(campId, "campId");
        Objects.requireNonNull(remaining, "remaining");
        if (remaining.isEmpty()) {
            // 계약이 minItems 1 이다. 남은 stop 이 없으면 재계획이 풀 것도 없다.
            throw new IllegalArgumentException("남은 stop 없는 at-risk 는 발행하지 않습니다");
        }
        outbox.append(OutboxMessage.of(
                DeliveryAtRiskPayload.AGGREGATE_TYPE,
                routeId,
                DeliveryAtRiskPayload.EVENT_TYPE,
                DeliveryAtRiskPayload.SCHEMA_VERSION,
                routeId.toString(),
                DeliveryAtRiskPayload.of(routeId, campId, detectedAt, deviation, remaining,
                        margin)));
    }
}
