package com.dawnline.dispatch.domain.optimizer.rule;

import com.dawnline.dispatch.domain.optimizer.Feasibility;
import com.dawnline.dispatch.domain.optimizer.HardRule;
import com.dawnline.dispatch.domain.optimizer.RouteState;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.util.OptionalInt;

/**
 * 라우트당 stop 수 상한 (§6.3 {@code MAX_STOPS_PER_ROUTE}, HARD).
 *
 * <p>용량이 남아도 기사 한 명이 하루에 도는 지점 수에는 현실적 한계가 있다. 이 룰이 없으면
 * 최적화가 고정비를 아끼려고 차 한 대에 전부 몰아넣는다.
 */
public record MaxStopsPerRouteRule(String name, int priority, int max) implements HardRule {

    static MaxStopsPerRouteRule of(RuleDefinition definition) {
        RuleParams params = new RuleParams(definition.name(), definition.params());
        return new MaxStopsPerRouteRule(definition.name(), definition.priority(),
                params.requirePositiveInt("max"));
    }

    /** stop 수는 넣는 자리와 무관하다 — 어디에 끼워도 {@code stopCount + 1} 이다. */
    @Override
    public boolean positionIndependent() {
        return true;
    }

    /**
     * 이 룰은 자기 판정을 stop 수 상한 하나로 <strong>남김없이</strong> 요약할 수 있다 —
     * 그것이 이 룰의 전부이기 때문이다 ([ADR-038]).
     *
     * <p>그래서 클러스터러가 {@code max} 를 읽지 않고도 「라우트 하나에 몇 개까지인가」를 알 수
     * 있다. 룰은 여전히 데이터다(§6.3) — 열린 것은 파라미터가 아니라 질문이다.
     */
    @Override
    public OptionalInt routeStopCap() {
        return OptionalInt.of(max);
    }

    @Override
    public Feasibility check(Stop stop, VehicleSpec vehicle, RouteState state) {
        // 이 stop 을 붙이면 stopCount + 1 이 된다. 그 값이 상한을 넘는지 본다.
        return state.stopCount() < max
                ? Feasibility.ok()
                : Feasibility.violated(name, "stop 상한 %d 에 도달했습니다".formatted(max));
    }
}
