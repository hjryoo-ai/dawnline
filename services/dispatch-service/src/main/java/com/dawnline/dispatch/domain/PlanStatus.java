package com.dawnline.dispatch.domain;

import com.dawnline.common.error.IllegalStateTransitionException;
import java.util.Map;
import java.util.Set;

/**
 * 계획의 상태 (DESIGN.md §5.3).
 *
 * <pre>
 * REQUESTED ──▶ PLANNING ──▶ PLANNED ──▶ PUBLISHED
 *                  └──(예외/시간초과)──▶ FAILED (운영자 재실행 가능)
 * </pre>
 *
 * <h2>{@code FAILED → REQUESTED} 를 여는 이유</h2>
 * §5.3 이 "운영자 재실행 가능" 이라고 적었고, ADR-024 가 그 재실행이 성공하면
 * {@code plan.completed} 가 다시 나가 웨이브를 {@code PLAN_FAILED → PLANNED} 로 되돌린다고
 * 정했다. 되돌아갈 자리가 없으면 그 경로가 코드에 없는 것이다.
 *
 * <h2>{@code PLANNING} 에서 되돌아가는 것도 연다</h2>
 * 계획 중 인스턴스가 죽으면 {@code PLANNING} 으로 남는다(§5.3). 스케줄러가 10분 지난 것을
 * {@code REQUESTED} 로 되돌려 재실행한다 — 그 전이가 없으면 그 계획은 영원히 멈춘 채로 남고,
 * 그 웨이브의 주문은 아무 이벤트도 받지 못한다.
 */
public enum PlanStatus {

    /** 실행을 기다린다. */
    REQUESTED,
    /** 실행 중. 이 상태로 10분 이상 남아 있으면 죽은 것으로 본다 (§5.3). */
    PLANNING,
    /** 결과가 나왔고 아직 발행하지 않았다. */
    PLANNED,
    /** 발행까지 끝났다 (route.assigned · order.dispatched · plan.completed). 종결. */
    PUBLISHED,
    /** 예외·시간초과로 실패했다. 운영자가 재실행할 수 있다. */
    FAILED;

    private static final Map<PlanStatus, Set<PlanStatus>> ALLOWED = Map.of(
            REQUESTED, Set.of(PLANNING),
            PLANNING, Set.of(PLANNED, FAILED, REQUESTED),
            PLANNED, Set.of(PUBLISHED, FAILED),
            PUBLISHED, Set.of(),
            FAILED, Set.of(REQUESTED));

    /** 이 전이가 허용되는가. */
    public boolean canTransitionTo(PlanStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    /**
     * 전이를 강제한다.
     *
     * @param target 목표 상태
     */
    public PlanStatus transitionTo(PlanStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateTransitionException("RoutePlan", name(), target.name());
        }
        return target;
    }

    /** 종결됐는가. {@code PUBLISHED} 만 종결이다 — {@code FAILED} 는 재실행이 열려 있다. */
    public boolean isTerminal() {
        return this == PUBLISHED;
    }
}
