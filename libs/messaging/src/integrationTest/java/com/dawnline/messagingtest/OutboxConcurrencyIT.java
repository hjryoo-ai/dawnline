package com.dawnline.messagingtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Ids;
import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.idempotency.ConsumeOutcome;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.outbox.OutboxAppender;
import com.dawnline.messaging.outbox.OutboxEvent;
import com.dawnline.messaging.outbox.OutboxMessage;
import com.dawnline.messaging.outbox.OutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 다중 인스턴스 안전성 — 릴레이의 {@code FOR UPDATE SKIP LOCKED} 와 멱등 소비의 경합 처리
 * (DESIGN.md §4.4, §8.5).
 *
 * <p>릴레이는 꺼 둔다. 백그라운드 폴링이 행을 집어 가면 여기서 만들려는 경합 상황이 재현되지 않는다.
 *
 * <p>이 두 테스트가 없으면 "SKIP LOCKED 라고 SQL 에 써 있다" 는 것 말고는 근거가 없다.
 * 실패 모드가 조용하기 때문에(성능 저하 / 중복 처리) 반드시 실제 DB 로 확인해야 한다.
 */
@SpringBootTest(
        classes = MessagingTestApplication.class,
        properties = "dawnline.messaging.outbox.enabled=false")
class OutboxConcurrencyIT extends MessagingIntegrationTestBase {

    /**
     * 테스트 페이로드.
     *
     * @param seq 순번
     */
    record Payload(int seq) {
    }

    @Autowired
    private OutboxAppender appender;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private IdempotentConsumer idempotentConsumer;

    @Autowired
    private Ids ids;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * 남긴 미발행 행을 정리한다.
     *
     * <p>컨테이너(=DB)를 다른 IT 클래스와 공유하기 때문에 필요하다. 여기 릴레이는 꺼져 있지만
     * {@code OutboxRelayIT} 의 릴레이는 켜져 있어서, 여기서 남긴 행이 그쪽 테스트의 Kafka 어설션을 오염시킨다.
     * 삭제 대신 "발행 완료로 표시" 하는 이유는 공개 포트만으로 끝낼 수 있어서다.
     */
    @AfterEach
    void 남은_미발행_행을_발행완료로_표시한다() {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(status -> {
            Instant now = Instant.now();
            for (OutboxEvent event : outboxRepository.lockUnpublishedBatch(1000)) {
                event.markPublished(now);
            }
        });
    }

    @Test
    void 릴레이_두_인스턴스가_같은_행을_집지_않는다() throws Exception {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(status -> {
            for (int i = 0; i < 6; i++) {
                appender.append(OutboxMessage.keyedByAggregate("Order", ids.newUuid(), "order.placed", 1,
                        new Payload(i)));
            }
        });

        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<List<UUID>> firstBatch = worker.submit(() -> transactions.execute(status -> {
                List<UUID> locked = idsOf(outboxRepository.lockUnpublishedBatch(3));
                firstLocked.countDown();
                awaitQuietly(release);
                return locked;
            }));

            assertThat(firstLocked.await(30, TimeUnit.SECONDS)).as("첫 트랜잭션이 행을 잠가야 한다").isTrue();

            // SKIP LOCKED 가 없으면 이 호출은 첫 트랜잭션이 끝날 때까지 블록된다.
            // 첫 트랜잭션은 release 를 기다리고 있으므로 교착이 되고, 테스트가 타임아웃으로 실패한다.
            List<UUID> secondBatch = transactions.execute(status -> idsOf(outboxRepository.lockUnpublishedBatch(3)));

            release.countDown();
            List<UUID> firstIds = firstBatch.get(30, TimeUnit.SECONDS);

            assertThat(firstIds).hasSize(3);
            assertThat(secondBatch).hasSize(3);
            assertThat(secondBatch).doesNotContainAnyElementsOf(firstIds);
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void 같은_이벤트를_두_스레드가_동시에_소비해도_한_번만_처리된다() throws Exception {
        UUID eventId = ids.newUuid();
        EventEnvelope<Map<String, String>> envelope = new EventEnvelope<>(eventId, "order.placed", 1,
                Instant.parse("2026-08-29T13:20:11.482Z"), "order-service", eventId.toString(), null,
                Map.of("orderId", "o-1"));

        AtomicInteger executions = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ConsumeOutcome> left = pool.submit(() -> {
                awaitQuietly(start);
                return idempotentConsumer.consumeOnce(envelope, "dispatch-service", executions::incrementAndGet);
            });
            Future<ConsumeOutcome> right = pool.submit(() -> {
                awaitQuietly(start);
                return idempotentConsumer.consumeOnce(envelope, "dispatch-service", executions::incrementAndGet);
            });

            start.countDown();
            List<ConsumeOutcome> outcomes = List.of(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS));

            // 하나는 처리, 하나는 중복. INSERT ... ON CONFLICT DO NOTHING 이 유니크 인덱스에서 대기했다가
            // 선행 트랜잭션 커밋 후 0행을 받는 동작에 의존한다.
            assertThat(outcomes).containsExactlyInAnyOrder(ConsumeOutcome.PROCESSED, ConsumeOutcome.DUPLICATE);
            assertThat(executions.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<UUID> idsOf(List<OutboxEvent> events) {
        return events.stream().map(OutboxEvent::id).toList();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("래치 대기가 타임아웃됐습니다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("래치 대기가 중단됐습니다", e);
        }
    }
}
