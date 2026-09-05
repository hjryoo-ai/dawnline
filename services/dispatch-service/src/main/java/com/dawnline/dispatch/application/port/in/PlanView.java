package com.dawnline.dispatch.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 계획 조회 결과 (DESIGN.md §5.3 {@code GET /api/v1/plans/{planId}}).
 *
 * <p>운영자가 "이 웨이브는 어떻게 됐나" 에 답을 얻는 화면이다 — 비용·미배정·설명이 함께 온다.
 * 설명이 없으면 §6.3 이 룰을 데이터로 둔 이유가 사라진다.
 *
 * @param planId          계획 id
 * @param waveId          웨이브 id
 * @param campId          캠프 id
 * @param status          계획 상태
 * @param strategy        전략 이름
 * @param mode            실행 모드
 * @param ruleVersion     적용한 룰 버전
 * @param startedAt       시작 시각
 * @param finishedAt      종료 시각
 * @param totalCostKrw    총비용
 * @param assignedCount   배정된 주문 수
 * @param unassignedCount 미배정 주문 수
 * @param planDurationMs  계획 소요 시간(ms)
 * @param failureReason   실패 사유
 * @param routes          라우트 요약
 * @param explanations    설명 (§6.3)
 */
public record PlanView(UUID planId, UUID waveId, UUID campId, String status,
        @Nullable String strategy, @Nullable String mode, @Nullable Integer ruleVersion,
        @Nullable Instant startedAt, @Nullable Instant finishedAt, @Nullable Long totalCostKrw,
        @Nullable Integer assignedCount, @Nullable Integer unassignedCount,
        @Nullable Integer planDurationMs, @Nullable String failureReason,
        List<RouteSummary> routes, List<ExplanationView> explanations) {

    /**
     * 라우트 요약.
     *
     * @param routeId    라우트 id
     * @param vehicleId  차량 id
     * @param seqNo      계획 안의 순번
     * @param revision   개정 번호
     * @param stopCount  stop 수
     * @param distanceM  총 이동 거리(m)
     * @param durationS  총 소요 시간(초)
     * @param costKrw    비용
     */
    public record RouteSummary(UUID routeId, UUID vehicleId, int seqNo, int revision, int stopCount,
            int distanceM, int durationS, long costKrw) {
    }

    /**
     * 설명 한 줄 (§6.3).
     *
     * @param orderId   주문 id
     * @param outcome   {@code ASSIGNED} 또는 {@code UNASSIGNED}
     * @param ruleName  판정에 관여한 룰
     * @param vehicleId 배정된 차량
     * @param detail    추가 정보 (JSON 문자열)
     */
    public record ExplanationView(UUID orderId, String outcome, @Nullable String ruleName,
            @Nullable UUID vehicleId, String detail) {
    }
}
