package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.Money;

/**
 * 비용에 가산되는 룰 (DESIGN.md §6.3).
 *
 * <p>소프트 룰은 <strong>모두</strong> 평가해 합산한다 — 하드 룰과 달리 중단하지 않는다.
 * 페널티는 음수일 수 있다({@code PRIORITY_BOOST} 는 보너스다). 그래서 반환 타입이
 * {@link Money} 이고, {@code Money} 는 음수를 허용한다.
 */
public non-sealed interface SoftRule extends DispatchRule {

    /**
     * 이 배치의 페널티. 보너스면 음수다.
     *
     * @param stop    배치하려는 stop
     * @param vehicle 라우트의 차량
     * @param state   여기까지 쌓인 라우트 상태
     */
    Money penalty(Stop stop, VehicleSpec vehicle, RouteState state);
}
