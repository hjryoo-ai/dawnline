package com.dawnline.order.adapter.out.messaging;

import com.dawnline.messaging.outbox.OutboxAppender;
import com.dawnline.messaging.outbox.OutboxMessage;
import com.dawnline.order.application.port.out.OrderEvents;
import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderStatus;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * {@link OrderEvents} 의 outbox 구현 (CLAUDE.md 불변규칙 1, DESIGN.md §4.4).
 *
 * <p>{@link OutboxAppender#append} 는 {@code outbox_events} 에 행 하나를 INSERT 할 뿐이고,
 * 그 INSERT 는 호출한 유스케이스의 트랜잭션에 참여한다. 주문이 롤백되면 이벤트도 사라진다.
 * 브로커로 실제로 보내는 것은 릴레이의 일이다(§4.4).
 *
 * <p>파티션 키는 애그리거트가 정한다({@link Order#partitionKey()}) — 같은 주문의 이벤트가 같은
 * 파티션으로 가야 순서가 보장된다(§4.5).
 */
public class OutboxOrderEvents implements OrderEvents {

    private final OutboxAppender outbox;

    /**
     * @param outbox 이벤트 발행의 유일한 진입점
     */
    public OutboxOrderEvents(OutboxAppender outbox) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
    }

    @Override
    public void placed(Order order, Instant cutoffAt) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(cutoffAt, "cutoffAt");
        outbox.append(OutboxMessage.of(
                OrderPlacedPayload.AGGREGATE_TYPE,
                order.id(),
                OrderPlacedPayload.EVENT_TYPE,
                OrderPlacedPayload.SCHEMA_VERSION,
                order.partitionKey(),
                OrderPlacedPayload.of(order, cutoffAt)));
    }

    @Override
    public void cancelled(Order order, OrderStatus previousStatus, @Nullable String reason) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(previousStatus, "previousStatus");
        outbox.append(OutboxMessage.of(
                OrderCancelledPayload.AGGREGATE_TYPE,
                order.id(),
                OrderCancelledPayload.EVENT_TYPE,
                OrderCancelledPayload.SCHEMA_VERSION,
                order.partitionKey(),
                OrderCancelledPayload.of(order, previousStatus, reason)));
    }
}
