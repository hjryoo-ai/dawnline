package com.dawnline.ops.application.port.in;

import com.dawnline.ops.domain.AuditResolution;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 감사 {@code UNKNOWN} · 오래된 {@code PENDING} 을 사람이 닫는다 — 대상 행은 고치지 않고 해소 행을 더한다
 * (DESIGN.md §5.5 「감사 해소」, ADR-065).
 */
public interface ResolveAuditUseCase {

    /** {@code audit_logs.action}. */
    String ACTION = "RESOLVE_AUDIT";

    /** {@code audit_logs.target_type}. */
    String TARGET_TYPE = "AUDIT";

    /** {@code reason} 의 최대 길이 — 조기 마감의 {@code reason} 과 같다. */
    int MAX_REASON_LENGTH = 200;

    /**
     * @param actor      토큰의 {@code sub}
     * @param target     해소할 감사 행
     * @param resolution 판정
     * @param reason     근거 — 필수, {@value #MAX_REASON_LENGTH}자까지
     * @return 적힌 해소 행
     * @throws com.dawnline.common.error.DomainException 대상이 없거나(404) 해소할 수 없거나 이미 해소됐다(409) — 그때도
     *         {@code REJECTED} 해소 행이 남는다. {@code reason} 이 틀리면 400 이고 행이 남지 않는다
     */
    Resolved resolve(String actor, UUID target, AuditResolution resolution, String reason);

    /**
     * 적힌 해소 행.
     *
     * @param auditId       해소 행의 id — 응답 헤더 {@code X-Dawnline-Audit-Id} 가 된다
     * @param targetAuditId 해소한 행
     * @param resolution    판정
     * @param reason        근거
     * @param actor         누가
     * @param createdAt     언제
     */
    record Resolved(UUID auditId, UUID targetAuditId, AuditResolution resolution, String reason, String actor,
            Instant createdAt) {
        public Resolved {
            Objects.requireNonNull(auditId, "auditId");
            Objects.requireNonNull(targetAuditId, "targetAuditId");
            Objects.requireNonNull(resolution, "resolution");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }
}
