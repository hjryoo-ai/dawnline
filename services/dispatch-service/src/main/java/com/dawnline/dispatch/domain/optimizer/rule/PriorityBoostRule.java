package com.dawnline.dispatch.domain.optimizer.rule;

import com.dawnline.common.Money;
import com.dawnline.common.error.ValidationException;
import com.dawnline.dispatch.domain.optimizer.RouteState;
import com.dawnline.dispatch.domain.optimizer.SoftRule;
import com.dawnline.dispatch.domain.optimizer.Stop;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;

/**
 * 우선 고객을 앞 순서에 두면 보너스 (§6.3 {@code PRIORITY_BOOST}, SOFT).
 *
 * <h2>보너스가 순번에 따라 줄어드는 이유</h2>
 * §6.3 은 파라미터로 {@code bonusKrw} 하나만 준다. 그런데 <strong>상수 보너스는 총비용에서
 * 순서를 구별하지 못한다</strong> — 어디에 놓든 같은 금액이라 개선 단계가 우선 고객을 뒤로 밀어도
 * 비용이 그대로다. "앞 순서에 두면" 이라는 문장이 값에 들어가려면 순번이 식에 있어야 한다.
 *
 * <pre>
 * bonus(stop, position) = bonusKrw × priority ÷ position     (position 은 1부터)
 * </pre>
 *
 * <p>1번 자리는 전액, 2번은 절반, 4번은 1/4 이다. 단조 감소이고 정수 나눗셈이라 결정적이다.
 *
 * <p><strong>이 감쇠식은 §6.3 에 없는 이 구현의 선택이다.</strong> 벤치마크(§6.9)가 우선 고객의
 * 실제 배치 순번을 보여 주면 그때 다시 정한다 — 지금은 "순번이 값에 들어간다" 는 성질만 맞추고,
 * 정확한 곡선은 수치를 본 뒤에 고른다.
 */
public record PriorityBoostRule(String name, int priority, long bonusKrw) implements SoftRule {

    public PriorityBoostRule {
        if (bonusKrw < 0L) {
            throw ValidationException.field(name + ".params.bonusKrw", bonusKrw,
                    "보너스는 음수일 수 없습니다 — 부호는 이 룰이 붙입니다");
        }
    }

    static PriorityBoostRule of(RuleDefinition definition) {
        RuleParams params = new RuleParams(definition.name(), definition.params());
        return new PriorityBoostRule(definition.name(), definition.priority(),
                params.requireLong("bonusKrw"));
    }

    @Override
    public Money penalty(Stop stop, VehicleSpec vehicle, RouteState state) {
        if (stop.priority() <= 0) {
            return Money.ZERO;
        }
        long position = state.stopCount() + 1L;
        long bonus = Math.multiplyExact(bonusKrw, (long) stop.priority()) / position;
        return Money.krw(-bonus);
    }
}
