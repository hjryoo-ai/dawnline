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
 * 근무 종료 전에 캠프로 돌아올 수 있어야 한다 (§6.3 {@code SHIFT_WINDOW}, HARD).
 *
 * <p>판정 기준은 <strong>복귀</strong> 시각이다 — 마지막 stop 에 도착하는 시각이 아니라 캠프까지
 * 돌아오는 시각이다. 그 구간이 라우트의 일부라서 {@link RouteState} 가 캠프와 거리를 들고 있다.
 *
 * <p>{@code bufferMinutes} 는 계획과 현실의 차이를 흡수한다. 0 으로 두면 계획상 정확히 근무 종료에
 * 복귀하는 라우트가 만들어지고, 그것은 실제로는 매번 초과 근무다.
 */
public record ShiftWindowRule(String name, int priority, int bufferMinutes) implements HardRule {

    public ShiftWindowRule {
        if (bufferMinutes < 0) {
            throw ValidationException.field(name + ".params.bufferMinutes", bufferMinutes,
                    "버퍼는 음수일 수 없습니다");
        }
    }

    static ShiftWindowRule of(RuleDefinition definition) {
        RuleParams params = new RuleParams(definition.name(), definition.params());
        return new ShiftWindowRule(definition.name(), definition.priority(),
                params.requireInt("bufferMinutes"));
    }

    @Override
    public Feasibility check(Stop stop, VehicleSpec vehicle, RouteState state) {
        Instant latestReturn = vehicle.shift().end().minus(Duration.ofMinutes(bufferMinutes));
        Instant actualReturn = state.returnTimeIfAppended(stop);
        if (!actualReturn.isAfter(latestReturn)) {
            return Feasibility.ok();
        }
        return Feasibility.violated(name, "복귀 %s 가 근무 종료 %s − 버퍼 %d분을 넘깁니다"
                .formatted(actualReturn, vehicle.shift().end(), bufferMinutes));
    }
}
