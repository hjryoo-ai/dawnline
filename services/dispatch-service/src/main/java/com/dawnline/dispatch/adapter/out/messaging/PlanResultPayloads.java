package com.dawnline.dispatch.adapter.out.messaging;

import com.dawnline.dispatch.domain.RoutePlan;
import com.dawnline.dispatch.domain.optimizer.PlanResult;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code plan.completed.v1} · {@code plan.failed.v1} 페이로드
 * ([ADR-024](docs/adr/ADR-024-plan-completed-event.md)).
 *
 * <p>계약은 <strong>소비자인 fulfillment 가 Phase 2 에 먼저 정의했다.</strong> 여기서는
 * 만족시키기만 한다 — 자기에게 필요한 필드는 같은 major 안에서 추가만 할 수 있다(§4.7).
 */
public final class PlanResultPayloads {

    /** {@code plan.completed} 의 {@code eventType}. */
    public static final String COMPLETED_EVENT_TYPE = "plan.completed";

    /** {@code plan.failed} 의 {@code eventType}. */
    public static final String FAILED_EVENT_TYPE = "plan.failed";

    /** 페이로드 스키마 major. */
    public static final int SCHEMA_VERSION = 1;

    /** {@code outbox_events.aggregate_type}. */
    public static final String AGGREGATE_TYPE = "RoutePlan";

    private PlanResultPayloads() {
    }

    /**
     * 계획 완료.
     *
     * @param planId          계획 id
     * @param waveId          웨이브 id (파티션 키와 같아야 한다)
     * @param campId          캠프 id
     * @param strategy        전략 이름
     * @param mode            실행 모드
     * @param routeCount      라우트 수
     * @param assignedCount   배정된 주문 수
     * @param unassignedCount 미배정 주문 수
     * @param totalCostKrw    총비용
     * @param planDurationMs  계획에 걸린 시간(ms)
     */
    public record Completed(UUID planId, UUID waveId, UUID campId, String strategy, String mode,
            int routeCount, int assignedCount, int unassignedCount, long totalCostKrw,
            int planDurationMs) {
    }

    /**
     * 계획 실패.
     *
     * @param planId   계획 id
     * @param waveId   웨이브 id
     * @param campId   캠프 id
     * @param reason   실패 사유
     * @param failedAt 실패 시각
     */
    public record Failed(UUID planId, UUID waveId, UUID campId, String reason, String failedAt) {
    }

    /**
     * @param plan   계획
     * @param result 계획 결과
     */
    public static Completed completed(RoutePlan plan, PlanResult result) {
        return new Completed(plan.id(), plan.waveId(), plan.campId(),
                plan.strategy().orElseThrow(), plan.mode().orElseThrow().name(),
                result.routes().size(), result.assignedOrderCount(), result.unassigned().size(),
                result.totalCost().krw(),
                plan.planDurationMs().orElse(0));
    }

    /**
     * @param plan 계획
     */
    public static Failed failed(RoutePlan plan) {
        return new Failed(plan.id(), plan.waveId(), plan.campId(),
                plan.failureReason().orElse("UNKNOWN"),
                plan.finishedAt().orElse(Instant.EPOCH).toString());
    }
}
