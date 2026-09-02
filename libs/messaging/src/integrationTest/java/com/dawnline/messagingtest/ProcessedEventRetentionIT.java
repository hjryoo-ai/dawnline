package com.dawnline.messagingtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Ids;
import com.dawnline.messaging.idempotency.ProcessedEventCleaner;
import com.dawnline.messaging.idempotency.ProcessedEventRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code processed_events} 보존 14일 정리 (DESIGN.md §4.4, §7.1) — 실제 PostgreSQL 18.
 *
 * <p>단위 테스트({@code ProcessedEventCleanerTest})는 반복·경계 <em>로직</em>을 검증한다.
 * 여기서 확인하는 것은 인메모리 가짜가 흉내 낼 수 없는 세 가지다.
 *
 * <ol>
 *   <li>{@code DELETE ... WHERE ctid IN (SELECT ... LIMIT)} 가 PostgreSQL 에서 실제로 도는가 —
 *       복합 PK 테이블에서 "LIMIT 걸린 삭제" 를 흉내 내는 문장이라 문법·계획이 실물에서 확인돼야 한다.</li>
 *   <li>{@code timestamptz} 경계 비교가 JDBC 왕복을 거쳐도 {@code <} 그대로인가.</li>
 *   <li>마이그레이션 {@code V000_4} 가 만든 인덱스가 실제로 존재하고, 부분 인덱스의 조건이
 *       게이지 쿼리와 정확히 같은가 — 조건이 어긋나면 인덱스는 있어도 안 쓰인다.</li>
 * </ol>
 *
 * <p>전용 DB 를 쓴다. 이 테스트는 {@code processed_events} 를 통째로 비우므로 다른 IT 와 공유하면
 * 그쪽 멱등 기록을 지워 버린다.
 */
@SpringBootTest(classes = MessagingTestApplication.class)
class ProcessedEventRetentionIT extends MessagingIntegrationTestBase {

    private static final String DATABASE = "dawnline_retention";
    private static final String CONSUMER = "order-service.wave-closed";

    /** §4.4 가 정한 보존 기간. 기본값이 바뀌면 이 테스트가 먼저 깨져야 한다. */
    private static final Duration RETENTION = Duration.ofDays(14);

    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    /**
     * 전용 DB 로 갈아탄다.
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        useIsolatedDatabase(registry, DATABASE);
    }

    @Autowired
    private ProcessedEventRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearProcessedEvents() {
        transactions().executeWithoutResult(status ->
                entityManager.createNativeQuery("DELETE FROM processed_events").executeUpdate());
    }

    private TransactionTemplate transactions() {
        return new TransactionTemplate(transactionManager);
    }

    /** 시각을 고정한 정리기. 보존 경계는 {@code NOW - 14일} 이다. */
    private ProcessedEventCleaner cleaner(int batchSize, int maxBatchesPerRun) {
        return new ProcessedEventCleaner(repository, transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC), RETENTION, batchSize, maxBatchesPerRun);
    }

    private UUID record(Instant processedAt) {
        UUID eventId = Ids.newId();
        transactions().executeWithoutResult(status -> repository.markProcessed(eventId, CONSUMER, processedAt));
        return eventId;
    }

    private long countRows() {
        Number count = (Number) entityManager
                .createNativeQuery("SELECT count(*) FROM processed_events")
                .getSingleResult();
        return count.longValue();
    }

    @Test
    void 보존_경계보다_오래된_행만_지운다() {
        Instant threshold = NOW.minus(RETENTION);
        UUID 오래된 = record(threshold.minusSeconds(1));
        UUID 경계_정각 = record(threshold);
        UUID 안쪽 = record(threshold.plusSeconds(1));

        int deleted = cleaner(1000, 100).deleteExpired();

        assertThat(deleted).isEqualTo(1);
        // `processed_at < :threshold` 라 경계 정각은 남는다. JDBC 왕복 후에도 등호가 생기지 않는다.
        assertThat(repository.isProcessed(오래된, CONSUMER)).isFalse();
        assertThat(repository.isProcessed(경계_정각, CONSUMER)).isTrue();
        assertThat(repository.isProcessed(안쪽, CONSUMER)).isTrue();
    }

    @Test
    void 배치_상한이_걸린_삭제가_실제로_LIMIT_만큼만_지운다() {
        Instant old = NOW.minus(Duration.ofDays(30));
        for (int i = 0; i < 25; i++) {
            record(old.plusSeconds(i));
        }

        // 배치 10, 상한 2 → 이번 실행은 20건에서 멈춘다.
        assertThat(cleaner(10, 2).deleteExpired()).isEqualTo(20);
        assertThat(countRows()).isEqualTo(5);

        // 남은 5건은 다음 실행이 지운다.
        assertThat(cleaner(10, 2).deleteExpired()).isEqualTo(5);
        assertThat(countRows()).isZero();
    }

    @Test
    void 오래된_행부터_지운다() {
        Instant old = NOW.minus(Duration.ofDays(30));
        UUID 가장_오래된 = record(old);
        UUID 중간 = record(old.plusSeconds(10));
        UUID 가장_최근 = record(old.plusSeconds(20));

        assertThat(cleaner(1, 1).deleteExpired()).isEqualTo(1);

        assertThat(repository.isProcessed(가장_오래된, CONSUMER)).isFalse();
        assertThat(repository.isProcessed(중간, CONSUMER)).isTrue();
        assertThat(repository.isProcessed(가장_최근, CONSUMER)).isTrue();
    }

    @Test
    void 만료_대상이_없으면_아무_행도_건드리지_않는다() {
        record(NOW.minus(Duration.ofDays(1)));
        record(NOW);

        assertThat(cleaner(1000, 100).deleteExpired()).isZero();
        assertThat(countRows()).isEqualTo(2);
    }

    @Test
    void 마이그레이션이_보존_정리용_인덱스를_만든다() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT indexname, indexdef
                  FROM pg_indexes
                 WHERE tablename = 'processed_events'
                   AND indexname = 'ix_processed_events_cleanup'
                """).getResultList();

        assertThat(rows).hasSize(1);
        String definition = (String) rows.getFirst()[1];
        assertThat(definition).contains("processed_at");
        // 임계 시각이 실행할 때마다 움직이므로 부분 인덱스일 수 없다.
        assertThat(definition).doesNotContain("WHERE");
    }

    @Test
    void 마이그레이션이_만든_격리_게이지_인덱스의_조건이_게이지_쿼리와_같다() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT i.relname,
                       pg_get_expr(x.indpred, x.indrelid)
                  FROM pg_index x
                  JOIN pg_class i ON i.oid = x.indexrelid
                  JOIN pg_class t ON t.oid = x.indrelid
                 WHERE t.relname = 'outbox_events'
                   AND i.relname = 'ix_outbox_failed'
                """).getResultList();

        assertThat(rows).hasSize(1);
        // 게이지 쿼리는 `WHERE failed_at IS NOT NULL` 이다. 인덱스 조건이 이것과 다르면
        // PostgreSQL 은 부분 인덱스를 쓸 수 없다고 판단하고 조용히 순차 스캔으로 돌아간다.
        assertThat((String) rows.getFirst()[1]).isEqualTo("(failed_at IS NOT NULL)");
    }

    @Test
    void 운영_규모에서_격리_게이지_쿼리가_부분_인덱스를_탄다() {
        // 행을 채우고 통계를 갱신한 뒤에야 계획을 볼 수 있다. 빈 테이블에서는 순차 스캔이
        // 실제로 더 싸서 플래너가 그것을 고르는 것이 옳고, 그 상태의 계획을 단정하면
        // "인덱스가 안 먹는다" 는 틀린 결론이 나온다. 실측은 docs/benchmarks/phase1-retention-indexes.md
        // 참고 — 500행 근처에서 부분 인덱스로 갈린다.
        givenPublishedOutboxRows(5_000);

        String plan = explain("SELECT count(*) FROM outbox_events WHERE failed_at IS NOT NULL");

        assertThat(plan).contains("ix_outbox_failed");
        assertThat(plan).doesNotContain("Seq Scan on outbox_events");
    }

    @Test
    void 정리_삭제가_보존_인덱스를_탄다() {
        givenProcessedEventRows(5_000, NOW.minus(Duration.ofDays(30)));

        // ProcessedEventCleaner 가 실제로 보내는 문장이다. 인덱스가 없으면 매 배치가
        // 테이블 전체를 훑고 정렬하므로, 락을 줄이려고 배치로 쪼갠 설계가 오히려 손해가 된다.
        String plan = explain("""
                DELETE FROM processed_events
                 WHERE ctid IN (SELECT ctid FROM processed_events
                                 WHERE processed_at < now() - interval '14 days'
                                 ORDER BY processed_at LIMIT 1000)
                """);

        // 두 계획을 가르는 지점은 "만료 행 1000개를 어떻게 고르는가" 다.
        // 인덱스가 없으면 전체를 훑어 processed_at 으로 top-N 정렬한다(= Sort Key 가 나온다).
        // 있으면 인덱스 순서를 그대로 써서 LIMIT 에서 조기 종료한다(= Sort 가 사라진다).
        assertThat(plan).contains("Index Scan using ix_processed_events_cleanup");
        assertThat(plan).doesNotContain("Sort Key");
        // ctid 를 다시 힙에서 찾는 바깥쪽 스캔은 인덱스와 무관하다. 행 수에 따라 Tid Scan 이 되기도
        // Hash Join 이 되기도 하므로 여기서 계획을 단정하지 않는다.
    }

    /** 발행 완료 outbox 행을 채운다. 발행 완료라서 릴레이가 집지 않는다. */
    private void givenPublishedOutboxRows(int count) {
        transactions().executeWithoutResult(status -> entityManager.createNativeQuery("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, topic,
                                           partition_key, headers, payload, created_at, published_at)
                SELECT gen_random_uuid(), 'order', gen_random_uuid(), 'order.placed', 'order.placed.v1',
                       gen_random_uuid()::text, '{}'::jsonb, '{}'::jsonb, now(), now()
                  FROM generate_series(1, :count)
                """).setParameter("count", count).executeUpdate());
        analyze("outbox_events");
    }

    private void givenProcessedEventRows(int count, Instant processedAt) {
        transactions().executeWithoutResult(status -> entityManager.createNativeQuery("""
                INSERT INTO processed_events (event_id, consumer, processed_at)
                SELECT gen_random_uuid(), :consumer, :processedAt
                  FROM generate_series(1, :count)
                """)
                .setParameter("consumer", CONSUMER)
                .setParameter("processedAt", processedAt)
                .setParameter("count", count)
                .executeUpdate());
        analyze("processed_events");
    }

    /** 통계가 없으면 플래너는 기본 추정치로 판단한다 — 계획을 보려면 갱신이 먼저다. */
    private void analyze(String table) {
        transactions().executeWithoutResult(status ->
                entityManager.createNativeQuery("ANALYZE " + table).executeUpdate());
    }

    private String explain(String sql) {
        return transactions().execute(status -> {
            @SuppressWarnings("unchecked")
            List<String> rows = entityManager.createNativeQuery("EXPLAIN " + sql).getResultList();
            // DELETE 계획을 보려면 실제로 실행하지 않는 EXPLAIN 이어야 하고(ANALYZE 없이),
            // 그래도 트랜잭션 안에서 돌린 뒤 되돌린다.
            status.setRollbackOnly();
            return String.join(" ", rows);
        });
    }
}
