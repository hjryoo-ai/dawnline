package com.dawnline.dispatch.domain;

/**
 * 계획 실행 모드 (DESIGN.md §6.7 열화 모드).
 *
 * <p>{@code plan.completed.mode} 로 그대로 나간다. 열화가 <strong>보이지 않으면</strong>
 * "성수기에도 정시" 를 위해 무엇을 포기했는지 아무도 모르게 된다.
 */
public enum PlanMode {
    /** 개선 단계까지 전부 (§6.5 1~5단계). */
    FULL,
    /** 개선 단계 생략 (§6.7 — 컨슈머 랙이나 직전 계획 시간이 예산의 80%를 넘겼을 때). */
    FAST
}
