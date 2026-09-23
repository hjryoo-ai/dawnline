package com.dawnline.ops.domain;

/**
 * {@code rm_waves.status} (DESIGN.md §5.5). 선언 순서가 진행 순서다.
 *
 * <p>fulfillment 의 {@code waves.status} 에 있는 {@code CLOSING} 이 없다 — 그쪽 내부의 상태이고
 * 그것을 싣는 이벤트가 없다. {@code PLANNED} 가 {@code PLAN_FAILED} 뒤인 것은 실패한 계획이
 * 다시 돌아 성공할 수 있기 때문이다(§5.2).
 */
public enum WaveStatus {

    /** {@code fulfillment.planned} — 웨이브를 처음 이름으로 부른다. */
    OPEN,

    /** {@code wave.closed}. */
    CLOSED,

    /** {@code plan.failed}. */
    PLAN_FAILED,

    /** {@code plan.completed}. */
    PLANNED
}
