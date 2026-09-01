package com.dawnline.messagingtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.EventHeaders;
import com.dawnline.messaging.Topics;
import com.dawnline.messaging.idempotency.ConsumeOutcome;
import com.dawnline.messaging.idempotency.IdempotentConsumer;
import com.dawnline.messaging.idempotency.ProcessedEventRepository;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.messaging.outbox.OutboxAppender;
import com.dawnline.messaging.outbox.OutboxMessage;
import com.dawnline.messaging.outbox.OutboxRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * <strong>Phase 0 DoD 의 핵심 통합 테스트</strong>:
 * outbox INSERT → 릴레이 → Kafka 수신 → 멱등 소비 2회 호출 시 1회만 처리
 * (IMPLEMENTATION_PLAN.md Phase 0 DoD, DESIGN.md §4.4).
 *
 * <p>실제 PostgreSQL 18 과 Kafka 4.3 을 쓴다. 인메모리 가짜로는 이 경로에서 정말 중요한 것들
 * — {@code FOR UPDATE SKIP LOCKED} 생성 여부, {@code jsonb} 매핑, {@code ddl-auto=validate} 통과,
 * {@code ON CONFLICT DO NOTHING} 의 잠금 동작 — 을 하나도 검증하지 못한다.
 */
@SpringBootTest(classes = MessagingTestApplication.class)
class OutboxRelayIT extends MessagingIntegrationTestBase {

    /**
     * 컨테이너 기본 DB 를 쓴다 — 베이스는 데이터소스를 등록하지 않으므로 각 테스트가 명시한다.
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        useSharedDatabase(registry);
    }

    /**
     * 테스트 페이로드.
     *
     * @param orderId 주문 id
     * @param tier    서비스 티어
     */
    record OrderPlaced(String orderId, String tier) {
    }

    private static final String TOPIC = Topics.forEvent("order.placed", 1);
    private static final String CONSUMER = "fulfillment-service";

    static {
        // 운영 브로커는 자동 토픽 생성이 꺼져 있다. 여기서도 켜져 있다고 가정하지 않는다.
        createTopic(TOPIC, 3);
    }

    @Autowired
    private OutboxAppender appender;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private IdempotentConsumer idempotentConsumer;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private EventJson json;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void outbox에_쌓인_이벤트가_릴레이를_거쳐_Kafka에_도착하고_두_번_소비해도_한_번만_처리된다() {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        UUID orderId = UUID.fromString("01a04dad-80da-7f6e-a63a-e91c103516b0");

        // (1) 도메인 트랜잭션 안에서 outbox 에 기록한다.
        UUID eventId = transactions.execute(status -> appender.append(
                OutboxMessage.keyedByAggregate("Order", orderId, "order.placed", 1,
                        new OrderPlaced(orderId.toString(), "DAWN"))));
        assertThat(eventId).isNotNull();
        assertThat(eventId.version()).as("eventId 는 UUIDv7 이어야 한다(불변규칙 10)").isEqualTo(7);

        // (2) 릴레이가 발행하고 published_at 을 찍는다.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(unpublishedCount(transactions)).isZero());

        // (3) Kafka 에서 실제로 읽힌다. 봉투와 헤더가 §4.2 대로다.
        List<ConsumerRecord<String, String>> records =
                consumeAtLeast(TOPIC, "outbox-relay-it", 1, Duration.ofSeconds(30));
        assertThat(records).hasSize(1);

        ConsumerRecord<String, String> received = records.getFirst();
        assertThat(received.key()).as("파티션 키는 orderId (§4.1)").isEqualTo(orderId.toString());
        assertThat(header(received, EventHeaders.EVENT_TYPE)).isEqualTo("order.placed");
        assertThat(header(received, EventHeaders.SCHEMA_VERSION)).isEqualTo("1");

        EventEnvelope<JsonNode> envelope = json.readEnvelope(received.value());
        assertThat(envelope.eventId()).as("봉투의 eventId 는 outbox 행 id 그대로여야 멱등이 성립한다")
                .isEqualTo(eventId);
        assertThat(envelope.eventType()).isEqualTo("order.placed");
        assertThat(envelope.schemaVersion()).isEqualTo(1);
        assertThat(envelope.producer()).isEqualTo("order-service");
        assertThat(envelope.partitionKey()).isEqualTo(orderId.toString());
        assertThat(envelope.payload().get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(envelope.payload().get("tier").asString()).isEqualTo("DAWN");

        // (4) 같은 봉투를 두 번 소비해도 비즈니스 로직은 한 번만 실행된다 (§4.4, §8.5).
        AtomicInteger executions = new AtomicInteger();
        ConsumeOutcome first = idempotentConsumer.consumeOnce(envelope, CONSUMER, executions::incrementAndGet);
        ConsumeOutcome second = idempotentConsumer.consumeOnce(envelope, CONSUMER, executions::incrementAndGet);

        assertThat(first).isEqualTo(ConsumeOutcome.PROCESSED);
        assertThat(second).isEqualTo(ConsumeOutcome.DUPLICATE);
        assertThat(executions.get()).isEqualTo(1);
        assertThat(processedEvents.isProcessed(eventId, CONSUMER)).isTrue();
        assertThat(processedEvents.isProcessed(eventId, "tracking-service"))
                .as("소비자가 다르면 각자 한 번씩 처리할 수 있어야 한다").isFalse();
    }

    private long unpublishedCount(TransactionTemplate transactions) {
        Long count = transactions.execute(status -> outboxRepository.countUnpublished());
        return count == null ? 0L : count;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
