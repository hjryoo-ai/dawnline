package com.dawnline.ops.application.port.out;

import com.dawnline.ops.domain.AuditResult;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code audit_logs} — 모든 운영자 커맨드의 기록 (DESIGN.md §5.5 · §4.6 「DLQ 재처리」).
 *
 * <p>두 번 쓴다. 위임 <em>전에</em> {@link #open} 으로 {@link AuditResult#PENDING} 을 <strong>커밋하고</strong>,
 * 위임이 끝나면 {@link #close} 로 결과를 적는다. 각 호출은 자기 트랜잭션이다 — 위임을 감싸는 트랜잭션이
 * 있으면 위임과 기록 사이에 죽었을 때 {@code PENDING} 까지 함께 사라진다.
 *
 * <p>위임이 없는 커맨드(감사 해소, ADR-065)는 한 번 쓴다 — {@link #record}. 잠금 · 판정 · 삽입이 한 트랜잭션이다.
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
     * 결과가 이미 정해진 행을 한 번에 쓴다 — 위임이 없는 커맨드(감사 해소, ADR-065)는 {@code PENDING} 단계가 없다.
     * 호출자의 트랜잭션에 참여한다.
     *
     * @param entry  기록
     * @param result {@code PENDING} 이 아닌 결과
     */
    void record(Entry entry, AuditResult result);

    /**
     * 해소하려는 행을 잡는다({@code SELECT … FOR UPDATE}). <strong>호출자의 트랜잭션 안에서</strong> 부른다 — 같은 행을 두
     * 사람이 동시에 해소하면 둘째가 첫째의 커밋을 기다린 뒤 {@link #findResolution} 으로 그것을 본다(ADR-065 결정 3).
     *
     * @param id 감사 행
     * @return 잠근 행 — 없으면 비어 있다
     */
    Optional<Row> lockForResolution(UUID id);

    /**
     * @param target 해소 대상 감사 행
     * @return 그 행을 해소한 {@code SUCCEEDED} 해소 행의 id — 없으면 비어 있다
     */
    Optional<UUID> findResolution(UUID target);

    /**
     * 해소 판정에 필요한 만큼의 행.
     *
     * @param id        감사 행
     * @param action    {@code audit_logs.action}
     * @param result    지금의 결과
     * @param createdAt 기록 시각 — {@code PENDING} 이 오래됐는지를 이것으로 본다
     */
    record Row(UUID id, String action, AuditResult result, Instant createdAt) {
        public Row {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

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
