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

    /**
     * 이 룰의 판정이 <strong>순서가 아니라 집합</strong>에만 달렸는가 ([ADR-037]).
     *
     * <p>«참» 이면 이 stop 을 라우트의 <em>어느 자리에</em> 넣든 판정이 같다 — stop 수,
     * 누적 적재, 차량 속성처럼 <strong>넣고 난 뒤의 상태가 자리와 무관한</strong> 것만 보기
     * 때문이다. 반대로 근무창·약속창은 도착 시각을 보므로 자리가 바뀌면 판정이 바뀐다.
     *
     * <p>이 구별이 값을 하는 자리는 <strong>재삽입</strong>이다. «거짓» 인 룰은 자리마다 라우트를
     * 다시 만들어 봐야 알지만, «참» 인 룰이 이미 거절한 라우트는 <em>어느 자리도 볼 필요가
     * 없다.</em> 기본값이 «거짓» 인 것은 의도다 — 새 룰이 조용히 가지치기 대상이 되면
     * 결과가 바뀐다. 표시는 그 룰을 아는 사람이 직접 단다.
     */
    default boolean positionIndependent() {
        return false;
    }
}
