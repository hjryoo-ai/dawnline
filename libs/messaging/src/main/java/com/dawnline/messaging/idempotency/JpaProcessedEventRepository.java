package com.dawnline.messaging.idempotency;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link ProcessedEventRepository} 의 JPA 구현.
 *
 * <h2>왜 "조회 후 INSERT" 가 아니라 {@code ON CONFLICT DO NOTHING} 인가</h2>
 *
 * <p>동시에 같은 이벤트를 두 소비자 스레드(리밸런스 직후, 또는 파티션 재할당 중복 배달)가 받는 일이 있다.
 *
 * <ol>
 *   <li><strong>조회 후 INSERT</strong> — 두 트랜잭션이 동시에 "없음" 을 보고 둘 다 INSERT 를 시도한다.
 *       하나는 기본키 위반으로 실패한다. 즉 경합을 막지 못한다.</li>
 *   <li><strong>INSERT 후 예외 잡기</strong> — PostgreSQL 에서는 제약 위반이 발생하면 <em>트랜잭션 전체가
 *       중단 상태</em>가 된다. 예외를 잡아도 그 트랜잭션에서는 아무것도 더 할 수 없다.
 *       "중복이니 조용히 넘어가자" 가 불가능하다.</li>
 *   <li><strong>{@code INSERT ... ON CONFLICT DO NOTHING}</strong> — 채택. 갱신 행 수가 0이면 중복이다.
 *       충돌이 예외가 아니므로 트랜잭션이 살아 있고, 그대로 커밋해 오프셋을 진행시킬 수 있다.</li>
 * </ol>
 *
 * <p>선행 트랜잭션이 아직 커밋되지 않았다면 이 INSERT 는 유니크 인덱스에서 <strong>대기</strong>한다.
 * 그리고 선행이 커밋되면 0행(중복), 롤백되면 1행(내가 처리)이 된다. 정확히 원하는 동작이다.
 */
public class JpaProcessedEventRepository implements ProcessedEventRepository {

    private static final String INSERT_SQL = """
            INSERT INTO processed_events (event_id, consumer, processed_at)
            VALUES (:eventId, :consumer, :processedAt)
            ON CONFLICT (event_id, consumer) DO NOTHING
            """;

    private static final String EXISTS_SQL =
            "SELECT count(*) FROM processed_events WHERE event_id = :eventId AND consumer = :consumer";

    /**
     * 보존 만료 행 삭제 (§4.4).
     *
     * <p>{@code ctid} 로 대상을 먼저 고르고 그 물리 주소로 지운다. PK 가
     * {@code (event_id, consumer)} 복합키라 {@code DELETE ... LIMIT} 를 흉내 내려면 두 컬럼짜리
     * {@code IN} 을 써야 하는데, {@code ctid} 는 한 컬럼이고 그 자체가 힙 주소라 두 번째 조회가 없다.
     *
     * <p>{@code ORDER BY processed_at} 은 {@code ix_processed_events_cleanup} 을 타면서
     * 오래된 행부터 집게 한다 — 반복 호출이 매번 앞으로 나아가는 것을 보장한다.
     */
    private static final String DELETE_EXPIRED_SQL = """
            DELETE FROM processed_events
             WHERE ctid IN (
                   SELECT ctid
                     FROM processed_events
                    WHERE processed_at < :threshold
                    ORDER BY processed_at
                    LIMIT :limit)
            """;

    private final EntityManager entityManager;

    public JpaProcessedEventRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    }

    @Override
    public boolean markProcessed(UUID eventId, String consumer, Instant processedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(processedAt, "processedAt");
        int inserted = entityManager.createNativeQuery(INSERT_SQL)
                .setParameter("eventId", eventId)
                .setParameter("consumer", consumer)
                .setParameter("processedAt", processedAt)
                .executeUpdate();
        return inserted == 1;
    }

    @Override
    public boolean isProcessed(UUID eventId, String consumer) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(consumer, "consumer");
        Number count = (Number) entityManager.createNativeQuery(EXISTS_SQL)
                .setParameter("eventId", eventId)
                .setParameter("consumer", consumer)
                .getSingleResult();
        return count.longValue() > 0;
    }

    @Override
    public int deleteProcessedBefore(Instant processedAtBefore, int limit) {
        Objects.requireNonNull(processedAtBefore, "processedAtBefore");
        if (limit < 1) {
            throw new IllegalArgumentException("limit 은 1 이상이어야 합니다: " + limit);
        }
        return entityManager.createNativeQuery(DELETE_EXPIRED_SQL)
                .setParameter("threshold", processedAtBefore)
                .setParameter("limit", limit)
                .executeUpdate();
    }
}
