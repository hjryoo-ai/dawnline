package com.dawnline.order.application;

import com.dawnline.common.error.NotFoundException;
import com.dawnline.order.application.port.in.CancelOrderUseCase;
import com.dawnline.order.application.port.in.OrderView;
import com.dawnline.order.application.port.out.OrderEvents;
import com.dawnline.order.application.port.out.OrderRepository;
import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderStatus;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 취소 (DESIGN.md §5.1 {@code POST /api/v1/orders/{id}/cancel}).
 *
 * <p>상태 전이·이벤트 기록이 한 트랜잭션이다(불변규칙 1). 여기서는
 * {@link PlaceOrderTransaction} 처럼 별도 빈으로 쪼갤 필요가 없다 — 트랜잭션 밖에서 할 일이 없어
 * 자기 호출(self-invocation) 함정이 생기지 않는다.
 *
 * <p>취소 가능 여부를 여기서 검사하지 않는다. {@code order.cancel(at)} 이 상태 머신에게 묻고,
 * 허용되지 않으면 {@code IllegalStateTransitionException}(409)을 던진다(불변규칙 6).
 * 유스케이스가 상태를 다시 나열하면 전이표가 두 벌이 된다.
 */
public class CancelOrderService implements CancelOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(CancelOrderService.class);

    private final OrderRepository orders;
    private final OrderEvents events;
    private final Clock clock;

    /**
     * @param orders 주문 저장소
     * @param events 이벤트 발행 포트 (outbox)
     * @param clock  전이 시각 출처 (불변규칙 12)
     */
    public CancelOrderService(OrderRepository orders, OrderEvents events, Clock clock) {
        this.orders = Objects.requireNonNull(orders, "orders");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional
    public OrderView cancel(UUID orderId, @Nullable String reason) {
        Objects.requireNonNull(orderId, "orderId");

        Order order = orders.findById(orderId)
                .orElseThrow(() -> NotFoundException.of("Order", orderId));
        OrderStatus previousStatus = order.status();

        order.cancel(clock.instant());
        orders.update(order);
        events.cancelled(order, previousStatus, reason);

        log.info("주문을 취소했습니다. orderId={}, previousStatus={}", orderId, previousStatus);
        return OrderView.of(order);
    }
}
