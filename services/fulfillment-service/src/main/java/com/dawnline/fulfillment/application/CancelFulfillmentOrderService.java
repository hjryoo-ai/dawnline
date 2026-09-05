package com.dawnline.fulfillment.application;

import com.dawnline.fulfillment.application.port.in.CancelFulfillmentOrderUseCase;
import com.dawnline.fulfillment.application.port.out.FulfillmentOrderRepository;
import com.dawnline.fulfillment.domain.FulfillmentOrder;
import com.dawnline.fulfillment.domain.FulfillmentOrderStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code order.cancelled} 처리 (ADR-022).
 *
 * <p>세 갈래뿐이고 <strong>웨이브 상태를 보지 않는다</strong>. {@code order_count} 가 마감 시
 * 집계로 바뀌면서(ADR-025) "웨이브가 {@code OPEN} 이면 카운트를 줄인다" 는 분기가 사라졌다.
 * 이 서비스가 웨이브를 전혀 건드리지 않는다는 사실이 그 결정의 크기를 보여 준다.
 */
public class CancelFulfillmentOrderService implements CancelFulfillmentOrderUseCase {

    private final FulfillmentOrderRepository orders;

    /**
     * @param orders 주문 저장소
     */
    public CancelFulfillmentOrderService(FulfillmentOrderRepository orders) {
        this.orders = Objects.requireNonNull(orders, "orders");
    }

    @Override
    @Transactional
    public CancelOutcome cancel(UUID orderId, Instant cancelledAt) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(cancelledAt, "cancelledAt");

        Optional<FulfillmentOrder> existing = orders.findById(orderId);
        if (existing.isEmpty()) {
            // 취소 선착. placed_event_id 가 빈 행이 그 사실의 기록이고, 뒤늦게 오는 order.placed 는
            // 축 규칙이 무시한다 — 별도 마커 테이블이 필요 없는 이유다 (ADR-022).
            if (orders.insertIfAbsent(FulfillmentOrder.cancelledBeforePlaced(orderId, cancelledAt))) {
                return CancelOutcome.CANCELLED_BEFORE_PLACED;
            }
            // 그 틈에 order.placed 가 행을 만들었다. PK 가 직렬화했으니 다시 읽어 상태 머신을 적용한다.
            return cancelExisting(orders.findById(orderId).orElseThrow(), cancelledAt);
        }
        return cancelExisting(existing.get(), cancelledAt);
    }

    private CancelOutcome cancelExisting(FulfillmentOrder order, Instant cancelledAt) {
        if (order.status() == FulfillmentOrderStatus.CANCELLED) {
            // 중복 취소는 사실을 바꾸지 않는다. 예외로 만들면 정상 흐름이 사고처럼 보인다.
            return CancelOutcome.ALREADY_CANCELLED;
        }
        order.cancel(cancelledAt);
        orders.update(order);
        return CancelOutcome.CANCELLED;
    }
}
