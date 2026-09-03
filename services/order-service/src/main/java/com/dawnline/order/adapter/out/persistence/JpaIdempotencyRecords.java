package com.dawnline.order.adapter.out.persistence;

import com.dawnline.order.application.port.in.OrderAccepted;
import com.dawnline.order.application.port.out.IdempotencyClaim;
import com.dawnline.order.application.port.out.IdempotencyRecord;
import com.dawnline.order.application.port.out.IdempotencyRecords;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link IdempotencyRecords} 의 JPA 구현 (DESIGN.md §5.1, ADR-018·019).
 *
 * <h2>왜 엔티티가 아니라 네이티브 SQL 인가</h2>
 * 이 어댑터의 정확성은 {@code ON CONFLICT DO NOTHING} 한 절에 달려 있다. JPA 로는 표현되지 않는다 —
 * {@code merge} 는 "있으면 덮어쓴다" 라서 이미 완료된 응답을 뒤엎고, 그러면 두 번째 요청이 첫 번째의
 * 답을 지운 뒤 자기 답을 준다. 멱등이 아니다.
 *
 * <p>{@code DO NOTHING} 이 필요한 성질을 다 갖는지는 추측하지 않고 측정했다(ADR-019 §2):
 * 충돌 상대가 아직 커밋되지 않았으면 <strong>그 트랜잭션이 끝날 때까지 기다렸다가</strong>
 * 0행을 돌려주고, 기존 행은 건드리지 않는다. 0행이 곧 "내가 졌다" 는 신호다.
 * {@code IdempotencyRecordsIT} 가 두 연결로 그 동작을 다시 확인한다.
 *
 * <h2>왜 애플리케이션 {@code ObjectMapper} 인가</h2>
 * {@code response_body} 는 <strong>HTTP 응답을 그대로 재생하기 위한</strong> 값이다(§5.1 1단계).
 * 그러니 저장할 때와 응답을 쓸 때가 같은 매퍼여야 한다. 이벤트 계약용 {@code EventJson} 은 여기
 * 쓰지 않는다 — 그쪽은 서비스 간 계약이라 설정이 따로 굳어 있어야 한다.
 */
public class JpaIdempotencyRecords implements IdempotencyRecords {

    /**
     * {@code CAST(response_body AS text)} 로 읽는다. {@code ::text} 로 쓰면 Hibernate 의 명명 파라미터
     * 파서가 {@code :text} 를 파라미터로 본다.
     */
    private static final String FIND_SQL = """
            SELECT request_hash, response_code, CAST(response_body AS text)
              FROM idempotency_keys
             WHERE idem_key = :key
            """;

    private static final String COMPLETE_SQL = """
            INSERT INTO idempotency_keys
                   (idem_key, request_hash, response_code, response_body, created_at, expires_at)
            VALUES (:key, :hash, :code, CAST(:body AS jsonb), :createdAt, :expiresAt)
            ON CONFLICT (idem_key) DO NOTHING
            """;

    /**
     * 복합 PK 가 아니어도 {@code LIMIT} 을 건 삭제에는 {@code ctid} 가 필요하다 —
     * PostgreSQL 의 {@code DELETE} 에는 {@code LIMIT} 절이 없다.
     * {@code ORDER BY expires_at} 가 인덱스를 타는지는 EXPLAIN 으로 확인했다
     * (docs/benchmarks/phase1-idempotency-cleanup-index.md).
     */
    private static final String DELETE_EXPIRED_SQL = """
            DELETE FROM idempotency_keys
             WHERE ctid IN (
                   SELECT ctid FROM idempotency_keys
                    WHERE expires_at < :now
                    ORDER BY expires_at
                    LIMIT :limit)
            """;

    private final EntityManager entityManager;
    private final ObjectMapper json;

    /**
     * @param entityManager 현재 트랜잭션의 EntityManager
     * @param json          응답 본문 직렬화기 — 웹 어댑터가 응답을 쓸 때와 같은 매퍼
     */
    public JpaIdempotencyRecords(EntityManager entityManager, ObjectMapper json) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        Objects.requireNonNull(key, "key");
        List<?> rows = entityManager.createNativeQuery(FIND_SQL)
                .setParameter("key", key)
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = (Object[]) rows.getFirst();
        // request_hash 는 CHAR(64) 다. 공백 패딩이 남으면 지문 비교가 항상 실패해 모든 재요청이 422 가 된다.
        return Optional.of(new IdempotencyRecord(
                ((String) row[0]).trim(),
                ((Number) row[1]).intValue(),
                json.readValue((String) row[2], OrderAccepted.class)));
    }

    @Override
    public boolean complete(IdempotencyClaim claim, int responseCode, OrderAccepted response) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(response, "response");
        int affected = entityManager.createNativeQuery(COMPLETE_SQL)
                .setParameter("key", claim.key())
                .setParameter("hash", claim.requestHash())
                .setParameter("code", responseCode)
                .setParameter("body", json.writeValueAsString(response))
                .setParameter("createdAt", claim.createdAt())
                .setParameter("expiresAt", claim.expiresAt())
                .executeUpdate();
        return affected == 1;
    }

    @Override
    public int deleteExpired(Instant now, int batchSize) {
        Objects.requireNonNull(now, "now");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize 는 1 이상이어야 합니다: " + batchSize);
        }
        return entityManager.createNativeQuery(DELETE_EXPIRED_SQL)
                .setParameter("now", now)
                .setParameter("limit", batchSize)
                .executeUpdate();
    }
}
