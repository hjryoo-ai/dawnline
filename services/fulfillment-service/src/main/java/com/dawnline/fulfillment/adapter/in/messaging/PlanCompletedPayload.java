package com.dawnline.fulfillment.adapter.in.messaging;

import java.util.UUID;

/**
 * {@code plan.completed.v1} 페이로드 (§4.3, ADR-024).
 *
 * <p>fulfillment 가 쓰는 것은 {@code waveId} 뿐이다. 나머지는 계약을 그대로 받는다 —
 * 이 계약을 <strong>소비자인 이쪽이 먼저 정의했으므로</strong>(계약 README §1) 필드가 늘거나
 * 줄면 여기서 드러나야 한다.
 *
 * @param planId          완료된 계획
 * @param waveId          계획된 웨이브
 * @param campId          캠프
 * @param strategy        전략 이름
 * @param mode            계획 모드
 * @param routeCount      만들어진 라우트 수
 * @param assignedCount   배정된 주문 수
 * @param unassignedCount 배정되지 못한 주문 수 (§6.7 미배정률)
 * @param totalCostKrw    총비용
 * @param planDurationMs  계획 소요 시간
 */
public record PlanCompletedPayload(
        UUID planId,
        UUID waveId,
        UUID campId,
        String strategy,
        String mode,
        int routeCount,
        int assignedCount,
        int unassignedCount,
        long totalCostKrw,
        long planDurationMs) {
}
