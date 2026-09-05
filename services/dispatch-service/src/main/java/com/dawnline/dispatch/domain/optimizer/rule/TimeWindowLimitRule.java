package com.dawnline.dispatch.domain.optimizer.rule;

import com.dawnline.dispatch.domain.optimizer.Feasibility;
import com.dawnline.dispatch.domain.optimizer.HardRule;
import com.dawnline.dispatch.domain.optimizer.RouteState;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import com.dawnline.common.error.ValidationException;
import java.time.Duration;
import java.time.Instant;

/**
 * 약속창을 N분 이상 넘기면 배정하지 않는다 (§6.3 {@code TIME_WINDOW_LIMIT}, HARD).
 *
 * <p>{@code TIME_WINDOW_PENALTY}(소프트)와 짝이다. 소프트만 있으면 비용을 감수하고 얼마든지 늦출
 * 수 있고, 그러면 §8.1 의 정시율이 비용 최적화에 팔린다. 이 룰이 그 바닥을 만든다.
 *
 * <p>기준 시각은 <strong>도착</strong>이다 — 고객이 겪는 것은 물건이 도착한 시각이다.
 */
public record TimeWindowLimitRule(String name, int priority, int hardLimitMinutes)
        implements HardRule {

    public TimeWindowLimitRule {
        if (hardLimitMinutes < 0) {
            throw ValidationException.field(name + ".params.hardLimitMinutes", hardLimitMinutes,
                    "지각 상한은 음수일 수 없습니다");
        }
    }

    static TimeWindowLimitRule of(RuleDefinition definition) {
        RuleParams params = new RuleParams(definition.name(), definition.params());
        return new TimeWindowLimitRule(definition.name(), definition.priority(),
                params.requireInt("hardLimitMinutes"));
    }

    @Override
    public Feasibility check(Stop stop, VehicleSpec vehicle, RouteState state) {
        Instant arrival = state.arrivalIfAppended(stop);
        Instant limit = stop.promised().end().plus(Duration.ofMinutes(hardLimitMinutes));
        if (!arrival.isAfter(limit)) {
            return Feasibility.ok();
        }
        long late = Duration.between(stop.promised().end(), arrival).toMinutes();
        return Feasibility.violated(name,
                "지각 %d분이 상한 %d분을 넘깁니다".formatted(late, hardLimitMinutes));
    }
}
