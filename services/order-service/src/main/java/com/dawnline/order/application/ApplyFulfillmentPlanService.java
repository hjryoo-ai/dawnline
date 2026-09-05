package com.dawnline.order.application;

import com.dawnline.order.application.port.in.ApplyFulfillmentPlanUseCase;
import com.dawnline.order.application.port.out.OrderRepository;
import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderStatus;
import com.dawnline.order.domain.PromisedWindow;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code fulfillment.planned} 반영 (§5.2 6단계, ADR-017 「Phase 2 를 위한 경고」, ADR-020 결정 3).
 *
 * <h2>상태 전이와 데이터 부착은 다른 일이다</h2>
 * ADR-017 이 미리 적어 둔 함정이다. 이 이벤트가 늦게 도착해 주문이 이미 {@code DISPATCHED} 라면
 * 상태 전이는 stale 로 버려야 하지만, <strong>함께 온 약속 개정까지 버리면 고객이 보는 값이
 * 낡은 채로 남는다</strong>. 늦게 왔다고 사실이 아닌 것은 아니다.
 *
 * <p>그래서 두 가지를 나눈다 — 전이는 축 규칙을 따르고, 데이터는 전이 여부와 무관하게 반영한다.
 */
public class ApplyFulfillmentPlanService implements ApplyFulfillmentPlanUseCase {

    private static final Logger log = LoggerFactory.getLogger(ApplyFulfillmentPlanService.class);

    private final OrderRepository orders;

    /**
     * @param orders 주문 저장소
     */
    public ApplyFulfillmentPlanService(OrderRepository orders) {
        this.orders = Objects.requireNonNull(orders, "orders");
    }

    @Override
    @Transactional
    public PlanApplication planned(UUID orderId, @Nullable PromisedWindow window, boolean revised,
            Instant at) {

        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(at, "at");

        Optional<Order> found = orders.findById(orderId);
        if (found.isEmpty()) {
            return PlanApplication.ORDER_NOT_FOUND;
        }
        Order order = found.get();

        // 1. 데이터 부착 — 전이 여부와 무관하다.
        boolean dataApplied = false;
        if (revised && window != null && !order.status().isTerminal()) {
            order.revisePromise(window, at);
            dataApplied = true;
        }

        // 2. 상태 전이 — 축 규칙을 그대로 따른다 (ADR-017).
        if (order.status().hasProgressedPast(OrderStatus.PLANNED)) {
            if (!dataApplied) {
                return PlanApplication.STALE;
            }
            orders.update(order);
            return PlanApplication.STALE_BUT_DATA_APPLIED;
        }
        if (!order.status().canTransitionTo(OrderStatus.PLANNED)) {
            // 취소된 주문 등. 축 밖이라 전이도 개정도 하지 않는다.
            log.debug("계획을 반영할 수 없는 주문입니다. orderId={}, status={}", orderId, order.status());
            return PlanApplication.REJECTED;
        }
        order.markPlanned(at);
        orders.update(order);
        return PlanApplication.APPLIED;
    }

    @Override
    @Transactional
    public PlanApplication unserviceable(UUID orderId, String reason, Instant at) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(at, "at");

        Optional<Order> found = orders.findById(orderId);
        if (found.isEmpty()) {
            return PlanApplication.ORDER_NOT_FOUND;
        }
        Order order = found.get();
        if (!order.status().canTransitionTo(OrderStatus.FAILED)) {
            // 이미 취소됐거나 배달이 끝났다. 배차 불가로 덮지 않는다.
            log.debug("배차 불가를 반영할 수 없는 주문입니다. orderId={}, status={}", orderId, order.status());
            return PlanApplication.REJECTED;
        }
        order.markUnserviceable(reason, at);
        orders.update(order);
        return PlanApplication.APPLIED;
    }
}
