package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.Money;

/**
 * 배정에 실패한 주문에 붙는 비용 (DESIGN.md §6.3 {@code UNASSIGNED_PENALTY}).
 *
 * <h2>왜 {@link SoftRule} 이 아닌가</h2>
 * 심각도는 SOFT 지만 <strong>평가 시점이 다르다</strong> — 배정에 실패했으므로 차량도 라우트 상태도
 * 없다. {@code SoftRule} 서명에 끼우려면 둘 중 하나를 널 허용으로 열어야 하고, 그러면 <em>모든</em>
 * 소프트 룰이 "차량이 없을 수도 있다" 를 방어해야 한다.
 *
 * <p>이 페널티가 있어야 §6.1 의 목적함수가 성립한다. 없으면 "아무것도 배정하지 않는 계획" 의 비용이
 * 0 이라 언제나 최적이 된다.
 */
public non-sealed interface UnassignedRule extends DispatchRule {

    /**
     * 이 stop 을 배정하지 못했을 때의 비용.
     *
     * @param stop 배정하지 못한 stop
     */
    Money penalty(Stop stop);
}
