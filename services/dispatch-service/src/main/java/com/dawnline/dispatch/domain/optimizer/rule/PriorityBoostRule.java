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
 * 우선 고객에게 <strong>먼저</strong> 가면 보너스 (§6.3 {@code PRIORITY_BOOST}, SOFT).
 *
 * <h2>무엇으로 「먼저」를 재는가 — 순번이 아니라 시각이다</h2>
 * §6.3 은 파라미터로 {@code bonusKrw} 하나만 주고 「앞 순서에 두면」이라고 적는다. 상수 보너스는
 * 총비용에서 순서를 구별하지 못하므로(어디에 놓든 같은 금액이라 개선 단계가 우선 고객을 뒤로 밀어도
 * 비용이 그대로다) 그 문장이 값에 들어가려면 <em>무엇인가</em>가 식에 있어야 한다. 처음 구현은
 * <strong>순번</strong>을 넣었고({@code ÷ position}), 그림자 계측이 그것을 반증했다([ADR-040],
 * {@code docs/benchmarks/phase4-priority-boost.md}): {@code large} 에서 거리·시간·지각이
 * <em>전부</em> 좋아진 계획을 그 항 하나가 <strong>+64,018원</strong>으로 벌했는데, 두 계획의
 * 총비용 차이는 −58,084원이었다. <strong>재는 자가 재려는 차이보다 크게 흔들렸다.</strong>
 *
 * <pre>
 * bonus(stop) = bonusKrw × priority × τ ÷ (τ + t)
 *   t = 계획 시작부터 이 stop 의 계획 도착 시각까지의 분
 *   τ = halfLifeMinutes — t = τ 에서 보너스가 절반이다
 * </pre>
 *
 * <p>기준점은 {@link RouteState#planStartedAt()} 이지 라우트 출발 시각이 아니다. 계획 안의 모든
 * 라우트가 <strong>같은 0점</strong>을 봐야 「쪼개면 첫 자리가 늘어난다」가 생기지 않는다 — 라우트
 * 기준으로 재면 {@code ÷ position} 의 결함을 시각으로 다시 만드는 것이다.
 *
 * <h2>이 자가 서 있는 전제 — §2.2</h2>
 * <strong>조기 배송은 허용되고 지각만 벌한다</strong>(§2.2·§6.3). 그 모델 위에서만 「이르다」가
 * 그 자체로 좋은 것이고, 이 룰은 우선 고객에게만 그 값을 매긴다. 고객이 약속창을 고르는 티어가
 * 생기거나 조기 배송이 비용이 되면 <strong>이 자를 다시 정해야 한다</strong> — 그때의 후보는
 * 약속창 시작 기준이다(ADR-040 §대안).
 */
public record PriorityBoostRule(String name, int priority, long bonusKrw, long halfLifeMinutes)
        implements SoftRule {

    public PriorityBoostRule {
        if (bonusKrw < 0L) {
            throw ValidationException.field(name + ".params.bonusKrw", bonusKrw,
                    "보너스는 음수일 수 없습니다 — 부호는 이 룰이 붙입니다");
        }
        if (halfLifeMinutes <= 0L) {
            throw ValidationException.field(name + ".params.halfLifeMinutes", halfLifeMinutes,
                    "반감기는 양수여야 합니다 — 0 이면 보너스가 사라지고 음수면 감쇠가 뒤집힙니다");
        }
    }

    static PriorityBoostRule of(RuleDefinition definition) {
        RuleParams params = new RuleParams(definition.name(), definition.params());
        return new PriorityBoostRule(definition.name(), definition.priority(),
                params.requireLong("bonusKrw"), params.requirePositiveInt("halfLifeMinutes"));
    }

    @Override
    public Money penalty(Stop stop, VehicleSpec vehicle, RouteState state) {
        if (stop.priority() <= 0) {
            return Money.ZERO;
        }
        Instant arrival = state.arrivalIfAppended(stop);
        // 근무 시작이 계획보다 늦은 차량이 있으므로 도착이 계획 시작보다 앞설 수는 없지만,
        // 음수 t 는 감쇠를 1 보다 크게 만들므로 식이 스스로 막는다.
        long elapsed = Math.max(0L, Duration.between(state.planStartedAt(), arrival).toMinutes());
        long full = Math.multiplyExact(bonusKrw, (long) stop.priority());
        long bonus = Math.multiplyExact(full, halfLifeMinutes) / (halfLifeMinutes + elapsed);
        return Money.krw(-bonus);
    }
}
