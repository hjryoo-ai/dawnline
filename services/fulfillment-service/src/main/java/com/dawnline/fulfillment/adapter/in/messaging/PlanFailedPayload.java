package com.dawnline.fulfillment.adapter.in.messaging;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code plan.failed.v1} 페이로드 (§4.1, ADR-024).
 *
 * @param planId   실패한 계획
 * @param waveId   계획하려던 웨이브
 * @param campId   캠프
 * @param reason   실패 사유
 * @param message  사람이 읽을 상세 (선택)
 * @param failedAt 실패 시각
 */
public record PlanFailedPayload(
        UUID planId,
        UUID waveId,
        UUID campId,
        String reason,
        @Nullable String message,
        Instant failedAt) {
}
