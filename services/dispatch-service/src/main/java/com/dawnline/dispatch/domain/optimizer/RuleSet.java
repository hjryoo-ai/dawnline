package com.dawnline.dispatch.domain.optimizer;

import com.dawnline.common.Money;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 한 계획에 적용되는 룰 묶음 (DESIGN.md §6.3).
 *
 * <p>캠프별 오버라이드는 <strong>여기 오기 전에</strong> 병합된다 — 순수 함수는 "누가 이겼는지" 가
 * 아니라 "무엇이 적용되는지" 만 안다. 병합은 어댑터가 하고, 계획이 시작되면 이 묶음은 고정이다
 * (진행 중 계획은 시작 시점 스냅샷을 쓴다, §6.3).
 */
public final class RuleSet {

    private final List<HardRule> hard;
    private final List<SoftRule> soft;
    private final List<UnassignedRule> unassigned;
    private final int version;

    private RuleSet(List<HardRule> hard, List<SoftRule> soft, List<UnassignedRule> unassigned,
            int version) {
        this.hard = hard;
        this.soft = soft;
        this.unassigned = unassigned;
        this.version = version;
    }

    /**
     * 룰을 우선순위로 정렬해 묶는다.
     *
     * @param rules   적용할 룰들
     * @param version {@code dispatch_rules.rule_version}. 어떤 룰로 계획했는지를 설명에 남긴다
     */
    public static RuleSet of(List<? extends DispatchRule> rules, int version) {
        Objects.requireNonNull(rules, "rules");
        Comparator<DispatchRule> byPriority =
                Comparator.comparingInt(DispatchRule::priority).thenComparing(DispatchRule::name);
        return new RuleSet(
                rules.stream().filter(HardRule.class::isInstance).map(HardRule.class::cast)
                        .sorted(byPriority).toList(),
                rules.stream().filter(SoftRule.class::isInstance).map(SoftRule.class::cast)
                        .sorted(byPriority).toList(),
                rules.stream().filter(UnassignedRule.class::isInstance).map(UnassignedRule.class::cast)
                        .sorted(byPriority).toList(),
                version);
    }

    /** 룰이 하나도 없는 묶음. 베이스라인 전략 비교와 단위 테스트가 쓴다. */
    public static RuleSet empty() {
        return new RuleSet(List.of(), List.of(), List.of(), 0);
    }

    /**
     * 하드 룰을 우선순위대로 평가하고 <strong>첫 위반에서 멈춘다</strong>.
     *
     * @param stop    넣으려는 stop
     * @param vehicle 라우트의 차량
     * @param state   여기까지 쌓인 라우트 상태
     */
    public Feasibility check(Stop stop, VehicleSpec vehicle, RouteState state) {
        for (HardRule rule : hard) {
            Feasibility result = rule.check(stop, vehicle, state);
            if (!result.feasible()) {
                return result;
            }
        }
        return Feasibility.ok();
    }

    /**
     * 소프트 룰을 <strong>모두</strong> 평가해 합산한다. 보너스가 있으므로 음수일 수 있다.
     *
     * @param stop    배치하려는 stop
     * @param vehicle 라우트의 차량
     * @param state   여기까지 쌓인 라우트 상태
     */
    public Money penalty(Stop stop, VehicleSpec vehicle, RouteState state) {
        Money total = Money.ZERO;
        for (SoftRule rule : soft) {
            total = total.plus(rule.penalty(stop, vehicle, state));
        }
        return total;
    }

    /**
     * 배정하지 못한 stop 의 비용 (§6.1 의 {@code unassignedPenalty}).
     *
     * <p>이 값이 없으면 "아무것도 배정하지 않는 계획" 의 비용이 0 이라 언제나 최적이 된다.
     *
     * @param stop 배정하지 못한 stop
     */
    public Money unassignedPenalty(Stop stop) {
        Money total = Money.ZERO;
        for (UnassignedRule rule : unassigned) {
            total = total.plus(rule.penalty(stop));
        }
        return total;
    }

    /** 미배정 룰들 (우선순위 순). */
    public List<UnassignedRule> unassignedRules() {
        return unassigned;
    }

    /** 하드 룰들 (우선순위 순). {@link PlanValidator} 가 최종 라우트에 다시 돌린다. */
    public List<HardRule> hardRules() {
        return hard;
    }

    /** 소프트 룰들 (우선순위 순). */
    public List<SoftRule> softRules() {
        return soft;
    }

    /** 이 계획이 쓴 룰 버전. */
    public int version() {
        return version;
    }
}
