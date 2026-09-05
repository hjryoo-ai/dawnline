package com.dawnline.fulfillment.application.port.in;

import java.util.UUID;

/**
 * {@code plan.completed}·{@code plan.failed} 를 받아 웨이브의 계획 상태를 기록한다
 * ([ADR-024](docs/adr/ADR-024-plan-completed-event.md)).
 *
 * <p>이 전이가 발화해야 [ADR-023](docs/adr/ADR-023-fulfillment-retention.md) 의 정리 배치가
 * {@code PLANNED} 주문 행을 지울 수 있다. 없으면 보존 정책이 조용히 무한 보존이 된다.
 */
public interface RecordPlanResultUseCase {

    /**
     * 계획이 끝났다 ({@code CLOSED}/{@code PLAN_FAILED} → {@code PLANNED}).
     *
     * @param waveId 웨이브 id
     */
    PlanResultOutcome completed(UUID waveId);

    /**
     * 계획이 실패했다 ({@code CLOSED} → {@code PLAN_FAILED}).
     *
     * @param waveId 웨이브 id
     */
    PlanResultOutcome failed(UUID waveId);

    /** 처리 결과. */
    enum PlanResultOutcome {

        /** 전이했다. */
        APPLIED,

        /**
         * 철 지난 이벤트라 무시했다 (ADR-024 결정 4).
         *
         * <p>재실행이 있으면 1회차 {@code plan.failed} 가 2회차 {@code plan.completed} 보다 늦게
         * 도착할 수 있다 — 두 이벤트가 다른 토픽이라 순서가 보장되지 않는다. 그대로 두면
         * <strong>라우트가 이미 나간 웨이브가 실패로 표시된다.</strong>
         */
        STALE,

        /** 그 웨이브를 모른다. 아직 마감되지 않았거나 이미 정리됐다. */
        WAVE_NOT_FOUND
    }
}
