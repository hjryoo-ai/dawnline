package com.dawnline.dispatch.domain.optimizer;

/**
 * 계획 전략 (DESIGN.md §6.6).
 *
 * <p>새 전략은 <strong>이 인터페이스 구현 + 등록</strong>만으로 추가된다. 그래야 §6.9 의 비교표가
 * 전략을 늘려도 같은 모양으로 유지된다.
 *
 * <p>§6.6 의 스케치는 {@code plan(problem, budget)} 이었는데 예산은 이미
 * {@link PlanningProblem} 안에 있다(3-1). 인자를 둘로 두면 <em>서로 다른</em> 예산 두 개를 넘길
 * 수 있고, 그러면 어느 쪽이 이기는지가 구현마다 달라진다.
 */
public interface DispatchStrategy {

    /** 전략 이름. {@code route_plans.strategy} 와 {@code route.assigned.strategy} 에 그대로 나간다. */
    String name();

    /**
     * 계획한다. 같은 입력(같은 {@code seed} 포함)이면 같은 결과를 내야 한다 (불변규칙 12).
     *
     * @param problem 계획 입력 전부
     */
    PlanResult plan(PlanningProblem problem);
}
