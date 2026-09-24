package com.dawnline.ops.application.port.out;

import com.dawnline.ops.domain.AuditResult;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code audit_logs} — 모든 운영자 커맨드의 기록 (DESIGN.md §5.5 · §4.6 「DLQ 재처리」).
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
     * @param action     {@code RUN_PLAN}·{@code DLQ_REPLAY} 등
     * @param targetType {@code WAVE}·{@code ORDER}·{@code EVENT}
     * @param targetId   대상 — 재처리할 DLQ 레코드를 읽지 못했으면 비어 있다(V1 이 허용한다)
     * @param request    커맨드의 인자 — 코어의 응답 본문도 이벤트의 value 도 넣지 않는다
     * @param createdAt  기록 시각
     */
    record Entry(UUID id, String actor, String action, String targetType, @Nullable UUID targetId,
            Map<String, Object> request, Instant createdAt) {
        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(targetType, "targetType");
            request = Map.copyOf(request);
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }
}
