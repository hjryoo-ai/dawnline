package com.dawnline.dispatch.domain.optimizer;

/**
 * 위반하면 배정할 수 없는 룰 (DESIGN.md §6.3).
 *
 * <p>평가 순서는 {@code priority} 오름차순이고 <strong>첫 위반에서 중단</strong>한다 — 사유를 하나만
 * 남기는 것이 운영자에게 더 낫기 때문이다. "여덟 개 다 어겼다" 는 답이 아니다.
 */
public non-sealed interface HardRule extends DispatchRule {

    /**
     * 이 stop 을 이 라우트에 넣을 수 있는가.
     *
     * @param stop    넣으려는 stop
     * @param vehicle 라우트의 차량
     * @param state   여기까지 쌓인 라우트 상태
     */
    Feasibility check(Stop stop, VehicleSpec vehicle, RouteState state);
}
