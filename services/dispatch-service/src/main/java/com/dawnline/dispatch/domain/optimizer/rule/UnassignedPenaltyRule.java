package com.dawnline.dispatch.domain.optimizer.rule;

import com.dawnline.common.Money;
import com.dawnline.common.error.ValidationException;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.UnassignedRule;

/**
 * 배정하지 못한 주문의 비용 (§6.3 {@code UNASSIGNED_PENALTY}).
 *
 * <p>§6.1 의 목적함수가 이 항 없이는 성립하지 않는다 — 없으면 "아무것도 배정하지 않는 계획" 의
 * 비용이 0 이라 언제나 최적이다.
 *
 * <pre>
 * penalty(stop) = (baseKrw + perPriorityKrw × priority) × 주문 수
 * </pre>
 *
 * <p>주문 수를 곱하는 이유: 통합된 stop 하나를 못 실으면 <strong>그 안의 주문이 전부</strong>
 * 미배정이 된다. stop 단위로 세면 3건짜리 stop 을 버리는 것이 1건짜리를 버리는 것과 같아진다.
 */
public record UnassignedPenaltyRule(String name, int priority, long baseKrw, long perPriorityKrw)
        implements UnassignedRule {

    public UnassignedPenaltyRule {
        if (baseKrw < 0L) {
            throw ValidationException.field(name + ".params.baseKrw", baseKrw, "기본 비용은 음수일 수 없습니다");
        }
        if (perPriorityKrw < 0L) {
            throw ValidationException.field(name + ".params.perPriorityKrw", perPriorityKrw,
                    "우선도 가산은 음수일 수 없습니다");
        }
    }

    static UnassignedPenaltyRule of(RuleDefinition definition) {
        RuleParams params = new RuleParams(definition.name(), definition.params());
        return new UnassignedPenaltyRule(definition.name(), definition.priority(),
                params.requireLong("baseKrw"), params.requireLong("perPriorityKrw"));
    }

    @Override
    public Money penalty(Stop stop) {
        long perOrder = Math.addExact(baseKrw,
                Math.multiplyExact(perPriorityKrw, (long) stop.priority()));
        return Money.krw(Math.multiplyExact(perOrder, (long) stop.orderCount()));
    }
}
