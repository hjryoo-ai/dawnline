package com.dawnline.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.Ids;
import com.dawnline.order.application.IdempotencyKeyCleaner;
import com.dawnline.order.application.port.in.OrderAccepted;
import com.dawnline.order.application.port.out.IdempotencyClaim;
import com.dawnline.order.application.port.out.IdempotencyRecord;
import com.dawnline.order.application.port.out.IdempotencyRecords;
import com.dawnline.order.domain.OrderStatus;
import com.dawnline.order.domain.ServiceTier;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code idempotency_keys} 쓰기·읽기·정리 (DESIGN.md §5.1, ADR-018·019) — 실제 PostgreSQL 18.
 *
 * <p>이 테스트가 없으면 확인되지 않는 것들이다.
 *
 * <ol>
 *   <li>{@code ON CONFLICT DO NOTHING} 이 <strong>커밋되지 않은</strong> 같은 키의 삽입을 기다렸다가
 *       0행을 돌려주는가. 멱등의 마지막 방어선이고, 단위 테스트로는 문자열만 볼 수 있다.</li>
 *   <li>{@code CAST(:body AS jsonb)} 로 바인딩한 값이 진짜 {@code jsonb} 로 저장되는가.</li>
 *   <li>{@code CHAR(64)} 인 {@code request_hash} 가 공백 패딩 없이 돌아오는가 — 패딩이 남으면
 *       지문 비교가 <em>항상</em> 실패해 모든 재요청이 422 가 된다.</li>
 *   <li>{@code TIMESTAMPTZ} 왕복에서 {@link Instant} 가 그대로인가.</li>
 *   <li>정리 배치가 만료된 행<em>만</em> 지우는가 (ADR-019).</li>
 * </ol>
 */
@SpringBootTest(classes = OrderApplication.class)
@DisplayName("IdempotencyRecordsIT — 멱등 기록 쓰기·경합·정리")
class IdempotencyRecordsIT extends OrderIntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00.123456Z");

    @Autowired
    private IdempotencyRecords records;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions() {
        return new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void clear() {
        transactions().executeWithoutResult(status ->
                entityManager.createNativeQuery("DELETE FROM idempotency_keys").executeUpdate());
    }

    private static OrderAccepted accepted(UUID orderId) {
        return new OrderAccepted(orderId, OrderStatus.PLACED, ServiceTier.DAWN,
                NOW.plus(Duration.ofHours(15)), NOW.plus(Duration.ofHours(22)), NOW);
    }

    private static IdempotencyClaim claim(String key, String hash) {
        return new IdempotencyClaim(key, hash, NOW, NOW.plus(Duration.ofDays(7)));
    }

    private static String hash(char c) {
        return String.valueOf(c).repeat(64);
    }

    /**
     * 반환 타입을 고정한 조회. {@code transactions().execute(...)} 를 그대로 {@code assertThat} 에
     * 넣으면 타입 추론이 {@code Predicate} 오버로드와 헷갈린다.
     */
    private Optional<IdempotencyRecord> find(String key) {
        return transactions().execute(status -> records.find(key));
    }

    @Test
    void 처음_보는_키는_빈_값이다() {
        assertThat(find("없는-키")).isEmpty();
    }

    @Test
    void 완료_기록을_쓰고_응답까지_그대로_되살린다() {
        UUID orderId = Ids.newId();
        OrderAccepted response = accepted(orderId);

        Boolean claimed = transactions().execute(status ->
                records.complete(claim("idem-1", hash('a')), 201, response));

        assertThat(claimed).isTrue();
        IdempotencyRecord record = find("idem-1").orElseThrow();
        // CHAR(64) 는 조회 시 공백 패딩이 붙을 수 있다. 붙은 채로 비교하면 모든 재요청이 422 가 된다.
        assertThat(record.requestHash()).isEqualTo(hash('a')).hasSize(64);
        assertThat(record.responseCode()).isEqualTo(201);
        // TIMESTAMPTZ 왕복 후에도 마이크로초까지 살아 있어야 한다.
        assertThat(record.response()).isEqualTo(response);
    }

    @Test
    void 저장된_본문은_문자열이_아니라_jsonb_다() {
        transactions().execute(status -> records.complete(claim("idem-2", hash('b')), 201, accepted(Ids.newId())));

        String type = transactions().execute(status -> (String) entityManager
                .createNativeQuery("SELECT jsonb_typeof(response_body) FROM idempotency_keys WHERE idem_key = 'idem-2'")
                .getSingleResult());

        assertThat(type).isEqualTo("object");
    }

    @Test
    void 이미_기록이_있는_키는_두_번째_요청이_덮어쓰지_못한다() {
        UUID first = Ids.newId();
        transactions().execute(status -> records.complete(claim("idem-3", hash('c')), 201, accepted(first)));

        Boolean second = transactions().execute(status ->
                records.complete(claim("idem-3", hash('c')), 201, accepted(Ids.newId())));

        assertThat(second).isFalse();
        IdempotencyRecord record = find("idem-3").orElseThrow();
        assertThat(record.response().orderId()).as("첫 번째 응답이 그대로 남아야 한다").isEqualTo(first);
    }

    @Test
    void 커밋되지_않은_같은_키의_삽입을_기다렸다가_0행을_돌려준다() throws Exception {
        // ADR-019 §2 의 근거를 실제 배선으로 다시 확인한다. 기다리지 않고 바로 0행을 주거나
        // 제약 위반 예외를 던진다면, Redis 가 없는 동안 같은 키의 동시 요청이 둘 다 성공하거나
        // 500 이 된다. "기다렸다가 0행" 이어야 진 쪽이 롤백하고 409 를 줄 수 있다.
        UUID winnerOrder = Ids.newId();
        CountDownLatch winnerInserted = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> winner = pool.submit(() -> transactions().execute(status -> {
                boolean claimed = records.complete(claim("race", hash('w')), 201, accepted(winnerOrder));
                winnerInserted.countDown();
                await(releaseWinner);
                return claimed;                      // 여기서 반환해야 커밋된다
            }));
            assertThat(winnerInserted.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> loser = pool.submit(() -> transactions().execute(status ->
                    records.complete(claim("race", hash('l')), 201, accepted(Ids.newId()))));

            // 아직 아무 답도 나오면 안 된다 — 승자가 커밋할 때까지 기다리는 중이어야 한다.
            assertThatThrownBy(() -> loser.get(700, TimeUnit.MILLISECONDS))
                    .as("커밋 전에 판정하면 두 요청이 둘 다 성공하거나 예외가 된다")
                    .isInstanceOf(TimeoutException.class);

            releaseWinner.countDown();
            assertThat(winner.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(loser.get(10, TimeUnit.SECONDS)).as("진 쪽은 0행이다").isFalse();
        } finally {
            releaseWinner.countDown();
            pool.shutdownNow();
        }

        IdempotencyRecord record = find("race").orElseThrow();
        assertThat(record.requestHash()).isEqualTo(hash('w'));
        assertThat(record.response().orderId()).isEqualTo(winnerOrder);
    }

    @Test
    void 트랜잭션이_롤백되면_기록도_사라진다() {
        // 주문 저장이 실패했는데 멱등 기록만 남으면, 그 키로는 영영 주문할 수 없게 된다.
        try {
            transactions().executeWithoutResult(status -> {
                records.complete(claim("idem-5", hash('e')), 201, accepted(Ids.newId()));
                throw new IllegalStateException("주문 저장 실패를 흉내 낸다");
            });
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessageContaining("흉내");
        }

        assertThat(find("idem-5")).isEmpty();
    }

    @Test
    void 정리는_만료된_행만_지운다() {
        Instant now = Instant.parse("2026-09-10T00:00:00Z");
        // expires_at = NOW + 7일 = 2026-09-10T00:00:00.123456Z → 아직 만료 전이다(마이크로초 차이).
        transactions().execute(status -> records.complete(claim("keep", hash('k')), 201, accepted(Ids.newId())));
        // 이쪽은 확실히 만료됐다.
        IdempotencyClaim expired = new IdempotencyClaim("gone", hash('g'),
                now.minus(Duration.ofDays(8)), now.minus(Duration.ofDays(1)));
        transactions().execute(status -> records.complete(expired, 201, accepted(Ids.newId())));

        Integer deleted = transactions().execute(status -> records.deleteExpired(now, 1000));

        assertThat(deleted).isEqualTo(1);
        assertThat(find("gone")).isEmpty();
        assertThat(find("keep")).isPresent();
    }

    @Test
    void 정리는_배치_크기를_넘지_않는다() {
        Instant now = Instant.parse("2026-09-10T00:00:00Z");
        for (int i = 0; i < 5; i++) {
            IdempotencyClaim expired = new IdempotencyClaim("bulk-" + i, hash('b'),
                    now.minus(Duration.ofDays(8)), now.minus(Duration.ofDays(1)).plusSeconds(i));
            transactions().execute(status -> records.complete(expired, 201, accepted(Ids.newId())));
        }

        Integer firstBatch = transactions().execute(status -> records.deleteExpired(now, 2));
        Integer secondBatch = transactions().execute(status -> records.deleteExpired(now, 2));

        assertThat(firstBatch).isEqualTo(2);
        assertThat(secondBatch).isEqualTo(2);
        assertThat(remaining()).isEqualTo(1);
    }

    @Test
    void 정리기가_남은_배치를_이어서_지운다() {
        Instant now = Instant.parse("2026-09-10T00:00:00Z");
        for (int i = 0; i < 5; i++) {
            IdempotencyClaim expired = new IdempotencyClaim("cleaner-" + i, hash('c'),
                    now.minus(Duration.ofDays(8)), now.minus(Duration.ofDays(1)).plusSeconds(i));
            transactions().execute(status -> records.complete(expired, 201, accepted(Ids.newId())));
        }
        IdempotencyKeyCleaner cleaner = new IdempotencyKeyCleaner(records, transactionManager,
                java.time.Clock.fixed(now, java.time.ZoneOffset.UTC), 2, 10);

        assertThat(cleaner.deleteExpired()).isEqualTo(5);
        assertThat(remaining()).isZero();
    }

    private long remaining() {
        Number count = transactions().execute(status ->
                (Number) entityManager.createNativeQuery("SELECT count(*) FROM idempotency_keys").getSingleResult());
        return count == null ? -1 : count.longValue();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("래치가 풀리지 않았습니다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
