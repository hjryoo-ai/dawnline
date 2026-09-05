package com.dawnline.order.application.port.in;

import com.dawnline.order.domain.PromisedWindow;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code fulfillment.planned} 를 주문에 반영한다 (§4.1, §5.2 6단계, ADR-020 결정 3).
 *
 * <p>{@code AdvanceOrderUseCase} 를 재사용하지 않는다. [ADR-017](docs/adr/ADR-017-order-state-machine-absorbs-out-of-order-events.md)
 * 이 「Phase 2 를 위한 경고」에 적어 둔 이유가 그것이다 — 그 포트는 <em>상태를 옮긴다</em> 만
 * 하는데, 이 이벤트는 상태만 나르지 않는다. 약속 개정과 배차 불가 사유가 함께 오고,
 * <strong>상태 전이가 stale 로 버려져도 그 데이터는 사실이다.</strong>
 */
public interface ApplyFulfillmentPlanUseCase {

    /**
     * 계획됨을 반영한다.
     *
     * @param orderId 주문 id
     * @param window  지금 유효한 약속창. {@code revised} 가 참일 때만 쓴다
     * @param revised 하류가 약속을 개정했는가
     * @param at      반영 시각
     */
    PlanApplication planned(UUID orderId, @Nullable PromisedWindow window, boolean revised, Instant at);

    /**
     * 배차 불가를 반영한다 — 주문을 {@code FAILED} 로 두고 사유를 남긴다 (§5.2 6단계).
     *
     * <p><strong>자동 재접수는 하지 않는다.</strong> 살릴지는 사람이 정한다.
     *
     * @param orderId 주문 id
     * @param reason  배차 불가 사유
     * @param at      반영 시각
     */
    PlanApplication unserviceable(UUID orderId, String reason, Instant at);

    /** 반영 결과. */
    enum PlanApplication {

        /** 적용했다. */
        APPLIED,

        /**
         * 상태 전이는 철 지났지만 <strong>데이터는 반영했다</strong>.
         *
         * <p>주문이 이미 {@code DISPATCHED} 인데 {@code fulfillment.planned} 가 늦게 오는 경우다.
         * 전이만 보면 버리는 것이 맞지만(ADR-017 축 규칙), 개정된 약속창까지 버리면 고객이 보는
         * 값이 낡은 채로 남는다.
         */
        STALE_BUT_DATA_APPLIED,

        /**
         * 상태 전이가 철 지났고 반영할 데이터도 없었다.
         *
         * <p>{@link #STALE_BUT_DATA_APPLIED} 와 나누는 이유는 <em>세는 값이 다르기</em> 때문이다 —
         * 이쪽은 순수한 순서 뒤바뀜이고, 저쪽은 순서 뒤바뀜에도 불구하고 고객의 약속이 갱신된
         * 경우다. 하나로 묶으면 "늦게 왔지만 값은 살렸다" 가 보이지 않는다.
         */
        STALE,

        /** 그 주문을 모른다. */
        ORDER_NOT_FOUND,

        /** 전이도 데이터 반영도 할 수 없다 (취소된 주문 등). */
        REJECTED
    }
}
