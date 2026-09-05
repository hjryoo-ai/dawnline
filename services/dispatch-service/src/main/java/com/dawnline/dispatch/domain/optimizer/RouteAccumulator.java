package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.Money;
import java.util.Objects;

/**
 * 라우트 하나를 쌓으면서 소프트 페널티를 함께 누적한다.
 *
 * <h2>왜 별도 타입인가</h2>
 * {@link RouteState} 는 <strong>룰이 보는 사실</strong>만 들고 있다 — 적재, 시각, 위치, 권역.
 * 소프트 페널티는 룰이 보는 사실이 아니라 룰이 <em>만들어 내는</em> 값이고, 그것을 {@code RouteState}
 * 에 넣으면 룰이 자기가 만든 값을 다시 보게 된다(예: {@code ZONE_AFFINITY} 가 누적 페널티를 보고
 * 판단을 바꾸는 일). 그래서 누적은 밖에서 한다.
 *
 * <p>페널티를 배치 <em>시점에</em> 재는 이유는 그 값이 배치 순간의 상태에 달렸기 때문이다 —
 * 지각 분은 그때의 도착 시각이고, 우선도 보너스는 그때의 순번이다. 다 쌓고 나서 다시 계산하면
 * 같은 값이 나오지 않는다.
 */
public final class RouteAccumulator {

    private final RuleSet rules;
    private final VehicleSpec vehicle;
    private RouteState state;
    private Money softPenalty = Money.ZERO;

    /**
     * @param rules   룰 묶음
     * @param vehicle 차량
     * @param depot   출발·복귀 캠프
     * @param distance 거리 제공자
     * @param startAt 출발 시각
     */
    public RouteAccumulator(RuleSet rules, VehicleSpec vehicle, CampDepot depot,
            DistanceProvider distance, java.time.Instant startAt) {

        this.rules = Objects.requireNonNull(rules, "rules");
        this.vehicle = Objects.requireNonNull(vehicle, "vehicle");
        this.state = RouteState.empty(vehicle, depot, distance, startAt);
    }

    /** 이 stop 을 넣을 수 있는가 (하드 룰). */
    public Feasibility check(Stop stop) {
        return rules.check(stop, vehicle, state);
    }

    /** 이 stop 을 <em>지금</em> 넣었을 때의 소프트 페널티. 넣지는 않는다. */
    public Money penaltyOf(Stop stop) {
        return rules.penalty(stop, vehicle, state);
    }

    /**
     * 넣는다. 하드 룰은 호출부가 이미 확인했다고 본다 — 여기서 또 확인하면 최근접 탐색이 모든
     * 후보에 대해 두 번 검사하게 된다.
     *
     * @param stop 넣을 stop
     */
    public void append(Stop stop) {
        softPenalty = softPenalty.plus(penaltyOf(stop));
        state = state.append(stop);
    }

    /** 현재 상태. 룰 평가와 최근접 탐색이 본다. */
    public RouteState state() {
        return state;
    }

    /** 아무것도 담지 않았는가. 빈 라우트는 만들지 않는다. */
    public boolean isEmpty() {
        return state.isEmpty();
    }

    /** 여기까지 쌓인 소프트 페널티. */
    public Money softPenalty() {
        return softPenalty;
    }

    /**
     * 라우트로 굳힌다. 거리·시간은 <strong>캠프 복귀를 포함</strong>한다 — 기사는 돌아와야 하고,
     * 그 구간의 비용도 이 라우트가 만든 비용이다.
     *
     * @param cost 비용 산식
     */
    public PlannedRoute toRoute(CostModel cost) {
        int distanceM = state.distanceWithReturn();
        int durationS = state.durationWithReturn();
        Money vehicleCost = cost.routeCost(vehicle, distanceM, durationS, state.stopCount());
        return new PlannedRoute(vehicle.id(), state.stops(), distanceM, durationS,
                vehicleCost.plus(softPenalty));
    }
}
