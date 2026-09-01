package com.dawnline.messaging.outbox;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * {@link OutboxRepository} 의 JPA 구현.
 *
 * <h2>왜 Spring Data 리포지토리가 아니라 네이티브 쿼리인가</h2>
 *
 * 릴레이의 정확성은 {@code FOR UPDATE SKIP LOCKED} 가 <strong>실제로 생성되는지</strong>에 달려 있다.
 * Spring Data 의 {@code @Lock(PESSIMISTIC_WRITE)} + 잠금 타임아웃 힌트 조합은 Hibernate 버전에 따라
 * {@code SKIP LOCKED} 로 번역될 수도, 그냥 {@code FOR UPDATE} 로 번역될 수도 있다.
 * 후자가 되면 릴레이 인스턴스들이 서로를 막고, 그 사실은 조용한 성능 저하로만 드러난다.
 * SQL 을 직접 쓰면 생성되는 문장이 곧 소스에 있고, 통합 테스트가 그 문장을 그대로 검증한다.
 *
 * <p>부수 효과 하나 더: Spring Data 리포지토리 인터페이스를 쓰면 서비스마다 리포지토리 스캔 범위에
 * {@code com.dawnline.messaging} 을 넣어야 한다. 플랫폼 라이브러리가 애플리케이션에 스캔 설정을
 * 강요하지 않는 편이 낫다.
 */
public class JpaOutboxRepository implements OutboxRepository {

    /**
     * {@code failed_at IS NULL} 이 격리 행을 조회에서 뺀다 (§4.6, ADR-015). 이 조건이 없으면
     * 결정적 실패 행이 {@code created_at} 순서 맨 앞에 계속 서서 뒤의 모든 이벤트를 막는다.
     * 부분 인덱스 {@code ix_outbox_unpublished} 의 조건과 정확히 같아야 인덱스를 탄다.
     */
    private static final String LOCK_BATCH_SQL = """
            SELECT id, aggregate_type, aggregate_id, event_type, topic, partition_key,
                   headers, payload, created_at, published_at, publish_attempts, failed_at
              FROM outbox_events
             WHERE published_at IS NULL AND failed_at IS NULL
             ORDER BY created_at, id
             LIMIT :batchSize
               FOR UPDATE SKIP LOCKED
            """;

    /** 격리 행은 세지 않는다 — 그건 {@link #countFailed()} 의 몫이다(§9.1 게이지 두 개가 겹치면 안 된다). */
    private static final String COUNT_UNPUBLISHED_SQL =
            "SELECT count(*) FROM outbox_events WHERE published_at IS NULL AND failed_at IS NULL";

    private static final String COUNT_FAILED_SQL =
            "SELECT count(*) FROM outbox_events WHERE failed_at IS NOT NULL";

    /**
     * 미발행이 없으면 {@code min()} 이 NULL 이고 {@code EXTRACT} 도 NULL 이므로 0 으로 바꾼다.
     * 타임스탬프를 자바로 꺼내지 않는 이유는 {@code timestamptz} 의 JDBC 매핑 타입이
     * 드라이버·Hibernate 버전에 따라 달라져 캐스팅이 불안정하기 때문이다. 숫자로 받으면 흔들릴 곳이 없다.
     */
    private static final String LAG_SECONDS_SQL = """
            SELECT COALESCE(EXTRACT(EPOCH FROM (now() - min(created_at))), 0)
              FROM outbox_events
             WHERE published_at IS NULL AND failed_at IS NULL
            """;

    private static final String DELETE_PUBLISHED_SQL =
            "DELETE FROM outbox_events WHERE published_at IS NOT NULL AND published_at < :threshold";

    private final EntityManager entityManager;

    public JpaOutboxRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    }

    @Override
    public void append(OutboxEvent event) {
        entityManager.persist(Objects.requireNonNull(event, "event"));
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"}) // JPA 의 Query#getResultList() 는 raw List 를 돌려준다.
    public List<OutboxEvent> lockUnpublishedBatch(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize 는 1 이상이어야 합니다: " + batchSize);
        }
        List result = entityManager.createNativeQuery(LOCK_BATCH_SQL, OutboxEvent.class)
                .setParameter("batchSize", batchSize)
                .getResultList();
        return (List<OutboxEvent>) result;
    }

    @Override
    public long countUnpublished() {
        return ((Number) entityManager.createNativeQuery(COUNT_UNPUBLISHED_SQL).getSingleResult()).longValue();
    }

    @Override
    public long countFailed() {
        return ((Number) entityManager.createNativeQuery(COUNT_FAILED_SQL).getSingleResult()).longValue();
    }

    @Override
    public double unpublishedLagSeconds() {
        return ((Number) entityManager.createNativeQuery(LAG_SECONDS_SQL).getSingleResult()).doubleValue();
    }

    @Override
    public int deletePublishedBefore(Instant publishedBefore) {
        Objects.requireNonNull(publishedBefore, "publishedBefore");
        return entityManager.createNativeQuery(DELETE_PUBLISHED_SQL)
                .setParameter("threshold", publishedBefore)
                .executeUpdate();
    }
}
