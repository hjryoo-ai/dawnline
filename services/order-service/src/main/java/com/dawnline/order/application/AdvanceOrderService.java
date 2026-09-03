package com.dawnline.order.application;

import com.dawnline.order.application.port.in.AdvanceOrderUseCase;
import com.dawnline.order.application.port.in.OrderProgress;
import com.dawnline.order.application.port.out.OrderRepository;
import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배송 진행 이벤트를 상태 머신에 적용한다 (DESIGN.md §5.1, ADR-017).
 *
 * <h2>판정 순서가 곧 규칙이다</h2>
 * <ol>
 *   <li>주문이 없으면 {@code ORDER_NOT_FOUND}.</li>
 *   <li>목표가 이미 지나온 지점이면 {@code STALE}. <strong>전이표보다 먼저</strong> 본다 —
 *       {@code DELIVERED} 인데 {@code DISPATCHED} 가 오는 것은 표에도 없지만 잘못된 상황이
 *       아니라 순서 뒤바뀜이다. 순서를 바꾸면 정상 배송이 알림에 올라간다.</li>
 *   <li>전이표에 있으면 적용, 없으면 {@code TRANSITION_NOT_ALLOWED}.</li>
 * </ol>
 *
 * <p>3번에 남는 실질적인 경우는 <em>취소된 주문에 배송 이벤트가 온 것</em>뿐이다. {@code CANCELLED}
 * 는 진행 축 밖이라 2번에 걸리지 않는다(ADR-017 §3) — 그리고 그것이 의도다. 취소된 주문의 소포가
 * 실제로 차에 실려 있다는 뜻이므로 조용히 삼키면 안 된다.
 *
 * <p>{@code @Transactional} 은 여기 있다. 리스너는 {@code IdempotentConsumer} 의 트랜잭션 안에서
 * 이 메서드를 부르고, 기본 전파가 {@code REQUIRED} 라 그 트랜잭션에 참여한다 —
 * {@code processed_events} 기록과 상태 변경이 함께 커밋되거나 함께 사라진다(불변규칙 2).
 */
public class AdvanceOrderService implements AdvanceOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(AdvanceOrderService.class);

    /** 이 유스케이스가 받는 목표 상태. 그 밖은 프로그래밍 오류다. */
    private static final Set<OrderStatus> SUPPORTED =
            Set.of(OrderStatus.DISPATCHED, OrderStatus.DELIVERED, OrderStatus.FAILED);

    private final OrderRepository orders;

    /**
     * @param orders 주문 저장소
     */
    public AdvanceOrderService(OrderRepository orders) {
        this.orders = Objects.requireNonNull(orders, "orders");
    }

    @Override
    @Transactional
    public OrderProgress advance(UUID orderId, OrderStatus target, Instant occurredAt) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (!SUPPORTED.contains(target)) {
            // 리스너가 잘못 부른 것이다. 이벤트 문제가 아니므로 거부가 아니라 예외다.
            throw new IllegalArgumentException("이 유스케이스가 다루는 상태가 아닙니다: " + target);
        }

        Optional<Order> found = orders.findById(orderId);
        if (found.isEmpty()) {
            log.warn("모르는 주문에 대한 배송 이벤트입니다. orderId={}, target={}", orderId, target);
            return OrderProgress.ORDER_NOT_FOUND;
        }

        Order order = found.get();
        OrderStatus current = order.status();
        if (current.hasProgressedPast(target)) {
            log.debug("철 지난 배송 이벤트입니다. orderId={}, current={}, target={}", orderId, current, target);
            return OrderProgress.STALE;
        }
        if (!current.canTransitionTo(target)) {
            log.warn("허용되지 않은 전이입니다. orderId={}, current={}, target={}", orderId, current, target);
            return OrderProgress.TRANSITION_NOT_ALLOWED;
        }

        apply(order, target, occurredAt);
        orders.update(order);
        log.info("주문 상태를 옮겼습니다. orderId={}, {} → {}", orderId, current, target);
        return OrderProgress.APPLIED;
    }

    /**
     * 상태 머신 메서드로만 전이한다 (불변규칙 6). 애그리거트에 범용 {@code advanceTo(status)} 를
     * 두지 않는 이유는 그것이 곧 세터이기 때문이다 — 이름 있는 메서드여야 호출부에서 무슨 사건이
     * 일어났는지 읽힌다.
     */
    private static void apply(Order order, OrderStatus target, Instant occurredAt) {
        switch (target) {
            case DISPATCHED -> order.markDispatched(occurredAt);
            case DELIVERED -> order.markDelivered(occurredAt);
            case FAILED -> order.markFailed(occurredAt);
            default -> throw new IllegalStateException("SUPPORTED 와 어긋납니다: " + target);
        }
    }
}
