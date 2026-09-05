package com.dawnline.fulfillment.application.port.in;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code order.cancelled} 를 받아 주문을 취소 상태로 둔다 (ADR-022).
 *
 * <p><strong>웨이브 카운트를 건드리지 않는다.</strong> {@code order_count} 는 마감 시
 * {@code status='PLANNED'} 만 세므로(ADR-025), 마감 전 취소는 자동으로 빠지고 마감 후 취소는
 * 이미 나간 {@code wave.closed} 의 숫자를 바꾸지 않는다. 분기가 아니라 구조로 보장된다.
 *
 * <p>웨이브에서 후보를 빼는 일은 §4.1 대로 dispatch 가 자기 {@code order.cancelled} 소비로 한다.
 */
public interface CancelFulfillmentOrderUseCase {

    /**
     * 취소한다.
     *
     * @param orderId     주문 id
     * @param cancelledAt 취소 시각
     * @return 무엇이 일어났는가
     */
    CancelOutcome cancel(UUID orderId, Instant cancelledAt);

    /** 취소 결과. */
    enum CancelOutcome {

        /** 있던 주문을 취소했다. */
        CANCELLED,

        /**
         * {@code order.placed} 보다 <strong>먼저</strong> 왔다 (§4.5 순서 뒤바뀜).
         *
         * <p>{@code placed_event_id} 가 빈 취소 행을 만든다. 뒤늦게 오는 {@code order.placed} 는
         * 축 규칙이 무시한다 — 별도 마커 테이블이 필요 없는 이유다(ADR-022).
         */
        CANCELLED_BEFORE_PLACED,

        /** 이미 취소된 주문이다. 중복 취소는 사실을 바꾸지 않는다. */
        ALREADY_CANCELLED
    }
}
