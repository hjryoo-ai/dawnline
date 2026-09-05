package com.dawnline.dispatch.domain.optimizer.rule;

import com.dawnline.common.Money;
import com.dawnline.common.error.ValidationException;
import com.dawnline.dispatch.domain.optimizer.RouteState;
import com.dawnline.dispatch.domain.optimizer.SoftRule;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;

/**
 * 라우트가 권역을 넘을 때마다 페널티 (§6.3 {@code ZONE_AFFINITY}, SOFT).
 *
 * <p>거리만 보면 권역 경계를 넘는 것이 이득일 수 있지만, 한 라우트가 여러 권역에 걸치면 운영이
 * 어려워진다(§6.5 2단계가 자르기를 우선하는 이유). 그 사실을 비용으로 표현한다.
 *
 * <p>페널티는 <strong>새 권역이 늘어나는 순간에만</strong> 한 번 붙는다 — 같은 권역의 두 번째
 * stop 에 또 붙이면 권역 안을 도는 것이 벌 받는다.
 */
public record ZoneAffinityRule(String name, int priority, long crossZonePenaltyKrw)
        implements SoftRule {

    public ZoneAffinityRule {
        if (crossZonePenaltyKrw < 0L) {
            throw ValidationException.field(name + ".params.crossZonePenaltyKrw", crossZonePenaltyKrw,
                    "권역 페널티는 음수일 수 없습니다");
        }
    }

    static ZoneAffinityRule of(RuleDefinition definition) {
        RuleParams params = new RuleParams(definition.name(), definition.params());
        return new ZoneAffinityRule(definition.name(), definition.priority(),
                params.requireLong("crossZonePenaltyKrw"));
    }

    @Override
    public Money penalty(Stop stop, VehicleSpec vehicle, RouteState state) {
        // 첫 stop 은 권역을 "넘는" 것이 아니라 정하는 것이다.
        if (state.isEmpty() || state.zones().contains(stop.zone())) {
            return Money.ZERO;
        }
        return Money.krw(crossZonePenaltyKrw);
    }
}
