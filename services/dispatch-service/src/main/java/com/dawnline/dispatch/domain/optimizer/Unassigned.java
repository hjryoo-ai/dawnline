package com.dawnline.dispatch.domain.optimizer;

import java.util.Objects;

/**
 * 배정되지 못한 주문 (DESIGN.md §6.2).
 *
 * <p>단위가 stop 이 아니라 <strong>주문</strong>인 이유: 판정은 stop 단위로 하지만(§6.3) 미배정은
 * 고객에게 일어나는 일이라 주문 단위로 세야 한다. stop 하나가 못 들어가면 그 stop 의 주문이
 * 전부 여기로 펼쳐진다.
 *
 * @param orderId  주문
 * @param ruleName 막은 룰 이름
 * @param reason   사유
 */
public record Unassigned(OrderId orderId, String ruleName, String reason) {

    public Unassigned {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(ruleName, "ruleName");
        Objects.requireNonNull(reason, "reason");
    }

    /**
     * 불가 판정에서 만든다.
     *
     * @param orderId     주문
     * @param feasibility 불가 판정
     */
    public static Unassigned from(OrderId orderId, Feasibility feasibility) {
        return new Unassigned(orderId, feasibility.ruleName(), feasibility.reason());
    }
}
