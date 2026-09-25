package com.dawnline.dispatch.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * dispatch 보존 정리의 고르기 · 삭제 · 셈 (ADR-059, DESIGN.md §7.1 「보존 표」).
 *
 * <p>지우는 단위는 표가 아니라 <strong>계획</strong>이다 — 고르는 메서드가 계획을 돌려주고, 지우는 메서드가 그 계획
 * 하나를 받는다. 부르는 쪽이 계획마다 트랜잭션을 연다(결정 6). 고르는 메서드는 전부 오래된 계획부터 {@code limit}
 * 개를 돌려준다 — {@code limit} 을 못 채운 반환이 「대상이 소진됐다」의 신호다.
 *
 * <p><strong>종결</strong>은 「계획이 발행됐거나 실패했고, 그 계획의 모든 stop 이 끝났다」다(결정 2).
 */
public interface DispatchRetention {

    /**
     * 설명 단계의 대상 — 종결이고 {@code finished_at} 이 임계 전이며 설명이 아직 남은 계획 (결정 3).
     *
     * @param finishedBefore 이 시각 전에 끝난 계획
     * @param limit          최대 계획 수 (1 이상)
     * @return 오래된 계획부터
     */
    List<PlanRef> settledPlansWithExplanationsFinishedBefore(Instant finishedBefore, int limit);

    /**
     * 계획 하나의 설명을 지운다.
     *
     * @param plan 계획
     * @return 지운 행 수
     */
    int deleteExplanations(PlanRef plan);

    /**
     * 후보 단계의 대상 — 종결이고 {@code finished_at} 이 임계 전이며 그 웨이브의 후보가 아직 남은 계획 (결정 3).
     *
     * @param finishedBefore 이 시각 전에 끝난 계획
     * @param limit          최대 계획 수 (1 이상)
     * @return 오래된 계획부터
     */
    List<PlanRef> settledPlansWithCandidatesFinishedBefore(Instant finishedBefore, int limit);

    /**
     * 계획 하나의 웨이브의 후보를 상태와 무관하게 전부 지운다.
     *
     * @param plan 계획
     * @return 지운 행 수
     */
    int deleteCandidates(PlanRef plan);

    /**
     * 90일 단계의 대상 — 종결이고 {@code finished_at} 이 임계 전인 계획.
     *
     * @param finishedBefore 이 시각 전에 끝난 계획
     * @param limit          최대 계획 수 (1 이상)
     * @return 오래된 계획부터
     */
    List<PlanRef> settledPlansFinishedBefore(Instant finishedBefore, int limit);

    /**
     * 상한의 대상 — {@code COALESCE(finished_at, started_at)} 가 임계 전인 계획, 종결과 무관하다 (결정 5).
     *
     * @param agedBefore 이 시각 전의 계획
     * @param limit      최대 계획 수 (1 이상)
     * @return 오래된 계획부터
     */
    List<PlanRef> plansAgedBefore(Instant agedBefore, int limit);

    /**
     * 계획 하나를 계열째 지운다 — 자식부터: {@code route_stop_orders} → {@code route_stops} → {@code routes} →
     * {@code plan_explanations} → 그 웨이브의 {@code dispatch_candidates} → {@code route_plans} (결정 6).
     *
     * @param plan 계획
     * @return 표마다 지운 행 수
     */
    PlanRows deletePlan(PlanRef plan);

    /**
     * 계획이 없는 웨이브의 후보 중 {@code updated_at} 이 임계 전인 것 — 상한이다 (결정 5). 계획이 있는 웨이브의
     * 후보는 계획과 함께만 지운다.
     *
     * @param updatedBefore 이 시각 전에 마지막으로 만진 행
     * @param limit         최대 행 수 (1 이상)
     * @return 지운 행 수
     */
    int deleteOrphanCandidatesUpdatedBefore(Instant updatedBefore, int limit);

    /**
     * {@code COALESCE(finished_at, started_at)} 가 임계 전인데 종결이 아닌 계획 수 —
     * {@code dawnline_route_plans_stuck} (결정 4). 설명 · 후보 단계의 고르기(의 종결 조건)와 서로의 여집합이다.
     *
     * @param agedBefore 보존의 임계
     * @return 걸린 계획 수
     */
    long countStuckPlansAgedBefore(Instant agedBefore);

    /**
     * 계획 하나.
     *
     * @param planId 계획 id
     * @param waveId 그 계획의 웨이브 — 후보는 이 값으로 지운다
     */
    record PlanRef(UUID planId, UUID waveId) {

        public PlanRef {
            Objects.requireNonNull(planId, "planId");
            Objects.requireNonNull(waveId, "waveId");
        }
    }

    /**
     * 계획 하나를 계열째 지운 행.
     *
     * @param stopOrders   {@code route_stop_orders}
     * @param stops        {@code route_stops}
     * @param routes       {@code routes}
     * @param explanations {@code plan_explanations}
     * @param candidates   {@code dispatch_candidates}
     * @param plans        {@code route_plans} — 0 이면 다른 인스턴스가 먼저 지웠다
     */
    record PlanRows(int stopOrders, int stops, int routes, int explanations, int candidates, int plans) {

        /** 합 — 트랜잭션 하나의 크기. */
        public int total() {
            return stopOrders + stops + routes + explanations + candidates + plans;
        }
    }
}
