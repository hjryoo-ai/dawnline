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
 * <h2>{@code PLANNING} 에서는 되돌아가지 않는다</h2>
 * 계획 하나는 트랜잭션 하나다(§5.3, ADR-024 후속 정정) — {@code PLANNING} 은 그 트랜잭션 안의 상태이고
 * 커밋되지 않는다. 계획 중 인스턴스가 죽으면 트랜잭션이 롤백되고 {@code wave.closed} 가 다시 전달된다. 되돌릴
 * {@code PLANNING} 이 남지 않으므로 그 전이(와 그것을 쓰던 정체 회수)를 지웠다(2026-09-26).
 */
public enum PlanStatus {

    /** 실행을 기다린다. */
    REQUESTED,
    /** 실행 중. 계획 트랜잭션 안에서만 있다 — 커밋되지 않는다 (§5.3). */
    PLANNING,
    /** 결과가 나왔고 아직 발행하지 않았다. */
    PLANNED,
    /** 발행까지 끝났다 (route.assigned · order.dispatched · plan.completed). 종결. */
    PUBLISHED,
    /** 예외·시간초과로 실패했다. 운영자가 재실행할 수 있다. */
    FAILED;

    private static final Map<PlanStatus, Set<PlanStatus>> ALLOWED = Map.of(
            REQUESTED, Set.of(PLANNING),
            PLANNING, Set.of(PLANNED, FAILED),
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
