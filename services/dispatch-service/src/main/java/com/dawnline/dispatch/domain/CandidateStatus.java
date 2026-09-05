package com.dawnline.dispatch.domain;

import com.dawnline.common.error.IllegalStateTransitionException;
import java.util.Map;
import java.util.Set;

/**
 * 계획 후보의 상태 (DESIGN.md §5.3, §6.10).
 *
 * <h2>축 규칙</h2>
 * order-service·fulfillment-service 와 같다(ADR-017, ADR-022) — <strong>진행 축에서 앞으로 가는
 * 전이는 건너뛰어도 허용하고, 뒤로 가는 전이는 무시하고 stale 로 센다.</strong> 이벤트가 다른
 * 토픽으로 오는 한 순서는 보장되지 않으므로(§4.5), 순서 역전을 흡수할 자리가 상태 머신이다.
 *
 * <pre>
 * PENDING 0 → PLANNED 1 → CANCELLED 2
 *           ↘ UNASSIGNED 1
 * </pre>
 *
 * <p>{@code PLANNED} 와 {@code UNASSIGNED} 는 같은 지점의 두 판정이라 서로 덮어쓰지 않는다.
 * {@code CANCELLED} 는 종결이고, <strong>취소된 후보의 행을 지우지 않는다</strong>(ADR-026) —
 * "주문 X 는 왜 라우트에 없나" 에 답할 수 있어야 한다.
 */
public enum CandidateStatus {

    /** 적재됐고 아직 계획되지 않았다. */
    PENDING(0),
    /** 라우트에 배정됐다. */
    PLANNED(1),
    /** 계획에서 배정되지 못했다 (§6.3 설명이 사유를 든다). */
    UNASSIGNED(1),
    /** 주문이 취소됐다. 종결. */
    CANCELLED(2);

    private static final Map<CandidateStatus, Set<CandidateStatus>> ALLOWED = Map.of(
            PENDING, Set.of(PLANNED, UNASSIGNED, CANCELLED),
            PLANNED, Set.of(CANCELLED),
            UNASSIGNED, Set.of(CANCELLED),
            CANCELLED, Set.of());

    private final int progress;

    CandidateStatus(int progress) {
        this.progress = progress;
    }

    /** 진행 축의 위치. 축 규칙이 이 값을 비교한다. */
    public int progress() {
        return progress;
    }

    /** 이미 이 지점을 지나왔는가. 지나왔으면 늦게 온 이벤트다(무시하고 stale 로 센다). */
    public boolean hasProgressedPast(CandidateStatus target) {
        return this.progress >= target.progress;
    }

    /** 이 전이가 허용되는가. */
    public boolean canTransitionTo(CandidateStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    /**
     * 전이를 강제한다.
     *
     * @param target 목표 상태
     */
    public CandidateStatus transitionTo(CandidateStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateTransitionException("DispatchCandidate", name(), target.name());
        }
        return target;
    }

    /** 계획 대상인가. {@code PENDING} 만 계획에 들어간다. */
    public boolean isPlannable() {
        return this == PENDING;
    }
}
