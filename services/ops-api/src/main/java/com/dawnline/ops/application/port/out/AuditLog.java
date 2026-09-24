package com.dawnline.ops.application.port.out;

import com.dawnline.ops.domain.AuditResult;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code audit_logs} — 모든 운영자 커맨드의 기록 (DESIGN.md §5.5).
 *
 * <p>두 번 쓴다. 위임 <em>전에</em> {@link #open} 으로 {@link AuditResult#PENDING} 을 <strong>커밋하고</strong>,
 * 위임이 끝나면 {@link #close} 로 결과를 적는다. 각 호출은 자기 트랜잭션이다 — 위임을 감싸는 트랜잭션이
 * 있으면 위임과 기록 사이에 죽었을 때 {@code PENDING} 까지 함께 사라진다.
 */
public interface AuditLog {

    /**
     * {@code PENDING} 행을 쓰고 커밋한다.
     *
     * @param entry 기록
     */
    void open(Entry entry);

    /**
     * {@code PENDING} 행을 결과로 닫는다. 이미 닫힌 행이면 예외다 — 결과는 한 번만 적힌다.
     *
     * @param id     감사 행
     * @param result {@code PENDING} 이 아닌 결과
     */
    void close(UUID id, AuditResult result);

    /**
     * 위임 전에 아는 것 전부.
     *
     * @param id         UUIDv7 — 코어 호출의 상관 헤더가 된다
     * @param actor      JWT 의 {@code sub}
     * @param action     {@code RUN_PLAN} 등
     * @param targetType {@code WAVE}·{@code ORDER}
     * @param targetId   대상
     * @param request    커맨드의 인자 — 코어의 응답 본문은 넣지 않는다
     * @param createdAt  기록 시각
     */
    record Entry(UUID id, String actor, String action, String targetType, UUID targetId,
            Map<String, Object> request, Instant createdAt) {
        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(targetType, "targetType");
            Objects.requireNonNull(targetId, "targetId");
            request = Map.copyOf(request);
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }
}
