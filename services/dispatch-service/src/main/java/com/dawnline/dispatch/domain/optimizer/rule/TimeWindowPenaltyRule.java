package com.dawnline.dispatch.domain.optimizer.rule;

import com.dawnline.common.Money;
import com.dawnline.common.error.ValidationException;
import com.dawnline.dispatch.domain.optimizer.RouteState;
import com.dawnline.dispatch.domain.optimizer.SoftRule;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import java.time.Duration;
import java.time.Instant;

/**
 * 약속창 초과 분당 페널티 (§6.3 {@code TIME_WINDOW_PENALTY}, SOFT).
 *
 * <p>{@code TIME_WINDOW_LIMIT}(하드)과 짝이다 — 이쪽이 "조금 늦는 것은 비싸다" 를, 저쪽이
 * "많이 늦는 것은 불가능하다" 를 말한다.
 */
public record TimeWindowPenaltyRule(String name, int priority, long penaltyPerMinuteKrw)
        implements SoftRule {

    public TimeWindowPenaltyRule {
        if (penaltyPerMinuteKrw < 0L) {
            throw ValidationException.field(name + ".params.penaltyPerMinuteKrw", penaltyPerMinuteKrw,
                    "지각 페널티는 음수일 수 없습니다");
        }
    }

    static TimeWindowPenaltyRule of(RuleDefinition definition) {
        RuleParams params = new RuleParams(definition.name(), definition.params());
        return new TimeWindowPenaltyRule(definition.name(), definition.priority(),
                params.requireLong("penaltyPerMinuteKrw"));
    }

    @Override
    public Money penalty(Stop stop, VehicleSpec vehicle, RouteState state) {
        Instant arrival = state.arrivalIfAppended(stop);
        if (!arrival.isAfter(stop.promised().end())) {
            return Money.ZERO;
        }
        long late = Duration.between(stop.promised().end(), arrival).toMinutes();
        return Money.krw(Math.multiplyExact(penaltyPerMinuteKrw, late));
    }
}
