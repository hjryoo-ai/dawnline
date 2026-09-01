package com.dawnline.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Ids;
import com.dawnline.messaging.EventEnvelope;
import com.dawnline.messaging.EventHeaders;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.messaging.support.InMemoryOutboxRepository;
import com.dawnline.messaging.support.MutableClock;
import com.dawnline.messaging.support.RecordingRecordPublisher;
import com.dawnline.messaging.support.TestTransactionManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * {@link OutboxBatchPublisher} — 배치 발행과 부분 실패 처리 (DESIGN.md §4.4, §4.5).
 */
class OutboxBatchPublisherTest {

    /**
     * 페이로드 예시.
     *
     * @param orderId 주문 id
     */
    record OrderPlaced(String orderId) {
    }

    private static final Instant NOW = Instant.parse("2026-08-29T13:20:11.482Z");
    private static final String TRACEPARENT = "00-c4474d0fc15e10af509d95cbda4b78b0-00f067aa0ba902b7-01";

    private final MutableClock clock = MutableClock.at(NOW);
    private final EventJson json = EventJson.standard();
    private final InMemoryOutboxRepository repository = new InMemoryOutboxRepository(clock);
    private final TestTransactionManager transactionManager = new TestTransactionManager();

    @Test
    void publishBatch_미발행이_없으면_0을_돌려주고_아무것도_보내지_않는다() {
        RecordingRecordPublisher publisher = RecordingRecordPublisher.alwaysSucceeding();

        assertThat(publisher(publisher, 500).publishBatch()).isZero();
        assertThat(publisher.sent()).isEmpty();
    }

    @Test
    void publishBatch_모두_성공하면_전부_발행완료로_표시한다() {
        appendOrders(3, TraceparentSupplier.NONE);
        RecordingRecordPublisher publisher = RecordingRecordPublisher.alwaysSucceeding();

        int published = publisher(publisher, 500).publishBatch();

        assertThat(published).isEqualTo(3);
        assertThat(publisher.sent()).hasSize(3);
        assertThat(repository.rows()).allMatch(OutboxEvent::isPublished);
        assertThat(transactionManager.commits()).isEqualTo(1);
    }

    @Test
    void publishBatch_중간에_실패하면_그_앞까지만_발행완료로_표시한다() {
        // §4.5 순서 보장: 뒤 이벤트를 먼저 확정하면 재시도된 앞 이벤트가 뒤늦게 들어가 순서가 뒤집힌다.
        appendOrders(4, TraceparentSupplier.NONE);
        RecordingRecordPublisher publisher = RecordingRecordPublisher.failingAt(2);

        int published = publisher(publisher, 500).publishBatch();

        assertThat(published).isEqualTo(2);
        List<OutboxEvent> rows = ordered();
        assertThat(rows.get(0).isPublished()).isTrue();
        assertThat(rows.get(1).isPublished()).isTrue();
        assertThat(rows.get(2).isPublished()).isFalse();
        // 4번째는 실제로 전송됐지만 표시하지 않는다 — 다음 폴링에서 재발행되고 중복은 소비자가 흡수한다.
        assertThat(rows.get(3).isPublished()).isFalse();
    }

    @Test
    void publishBatch_배치_크기를_넘지_않는다() {
        appendOrders(5, TraceparentSupplier.NONE);
        RecordingRecordPublisher publisher = RecordingRecordPublisher.alwaysSucceeding();

        assertThat(publisher(publisher, 2).publishBatch()).isEqualTo(2);
        assertThat(publisher.sent()).hasSize(2);
    }

    @Test
    void publishBatch_봉투에_outbox_행의_값을_그대로_담는다() {
        UUID eventId = appender(TraceparentSupplier.NONE).append(message(0));
        RecordingRecordPublisher publisher = RecordingRecordPublisher.alwaysSucceeding();
        clock.advance(Duration.ofSeconds(30));

        publisher(publisher, 500).publishBatch();

        RecordingRecordPublisher.Sent sent = publisher.sent().getFirst();
        assertThat(sent.topic()).isEqualTo("dawnline.order.placed.v1");
        EventEnvelope<JsonNode> envelope = json.readEnvelope(sent.value());
        assertThat(envelope.eventId()).isEqualTo(eventId);
        assertThat(envelope.eventType()).isEqualTo("order.placed");
        assertThat(envelope.schemaVersion()).isEqualTo(1);
        // 30초 뒤에 발행했지만 occurredAt 은 사건 시각이다 (§4.2).
        assertThat(envelope.occurredAt()).isEqualTo(NOW);
        assertThat(envelope.producer()).isEqualTo("order-service");
        assertThat(envelope.partitionKey()).isEqualTo(sent.key());
        assertThat(envelope.traceId()).isNull();
    }

    @Test
    void publishBatch_traceparent가_있으면_헤더와_봉투_traceId에_모두_전파한다() {
        appender(() -> Optional.of(TRACEPARENT)).append(message(0));
        RecordingRecordPublisher publisher = RecordingRecordPublisher.alwaysSucceeding();

        publisher(publisher, 500).publishBatch();

        RecordingRecordPublisher.Sent sent = publisher.sent().getFirst();
        assertThat(sent.headers()).containsEntry(EventHeaders.TRACEPARENT, TRACEPARENT);
        assertThat(sent.headers()).containsEntry(EventHeaders.EVENT_TYPE, "order.placed");
        assertThat(sent.headers()).containsEntry(EventHeaders.SCHEMA_VERSION, "1");
        assertThat(json.readEnvelope(sent.value()).traceId())
                .isEqualTo("c4474d0fc15e10af509d95cbda4b78b0");
    }

    @Test
    void publishBatch_오래된_행부터_보낸다() {
        OutboxAppender appender = appender(TraceparentSupplier.NONE);
        UUID first = appender.append(message(0));
        clock.advance(Duration.ofSeconds(1));
        UUID second = appender.append(message(1));
        RecordingRecordPublisher publisher = RecordingRecordPublisher.alwaysSucceeding();

        publisher(publisher, 500).publishBatch();

        List<UUID> sentIds = publisher.sent().stream()
                .map(sent -> json.readEnvelope(sent.value()).eventId())
                .toList();
        assertThat(sentIds).containsExactly(first, second);
    }

    @Test
    void publishBatch_봉투로_만들_수_없는_행이_있어도_그_앞까지는_발행하고_커밋한다() {
        // 독약 행: eventType 이 봉투 형식을 어겨 send() 가 *동기적으로* 터진다.
        // 예외가 밖으로 나가면 트랜잭션이 롤백돼 앞 두 건의 published_at 까지 날아가고,
        // 다음 폴링이 같은 배치를 또 집어 영원히 제자리걸음을 한다.
        OutboxAppender appender = appender(TraceparentSupplier.NONE);
        appender.append(message(0));
        clock.advance(Duration.ofSeconds(1));
        appender.append(message(1));
        clock.advance(Duration.ofSeconds(1));
        appendPoisonRow("OrderPlaced");
        clock.advance(Duration.ofSeconds(1));
        appender.append(message(2));
        RecordingRecordPublisher publisher = RecordingRecordPublisher.alwaysSucceeding();

        int published = publisher(publisher, 500).publishBatch();

        assertThat(published).isEqualTo(2);
        assertThat(publisher.sent()).hasSize(2);
        assertThat(transactionManager.commits()).isEqualTo(1);
        assertThat(transactionManager.rollbacks()).isZero();
        List<OutboxEvent> rows = ordered();
        assertThat(rows.get(0).isPublished()).isTrue();
        assertThat(rows.get(1).isPublished()).isTrue();
        assertThat(rows.get(2).isPublished()).isFalse();
        // 독약 행 뒤는 손대지 않는다 — 순서 보장(§4.5)상 건너뛸 수 없다.
        assertThat(rows.get(3).isPublished()).isFalse();
    }

    @Test
    void publishBatch_맨_앞이_봉투로_만들_수_없는_행이면_예외를_밖으로_던지지_않는다() {
        // 진행은 0이지만(사람이 그 행을 고쳐야 한다) 릴레이 스레드로 예외가 새지는 않는다.
        OutboxAppender appender = appender(TraceparentSupplier.NONE);
        appendPoisonRow("OrderPlaced");
        clock.advance(Duration.ofSeconds(1));
        appender.append(message(0));
        clock.advance(Duration.ofSeconds(1));
        appender.append(message(1));
        RecordingRecordPublisher publisher = RecordingRecordPublisher.alwaysSucceeding();

        int published = publisher(publisher, 500).publishBatch();

        assertThat(published).isZero();
        assertThat(publisher.sent()).isEmpty();
        assertThat(transactionManager.commits()).isEqualTo(1);
        assertThat(transactionManager.rollbacks()).isZero();
        assertThat(repository.rows()).noneMatch(OutboxEvent::isPublished);
    }

    /** 정상 경로로는 만들 수 없는 행이라 저장소에 직접 넣는다 (쓰기 경로가 막는지는 {@code OutboxMessageTest}). */
    private void appendPoisonRow(String badEventType) {
        repository.append(new OutboxEvent(
                new Ids(clock, new Random(7)).newUuid(), "Order", UUID.randomUUID(), badEventType,
                "dawnline.order.placed.v1", "key",
                "{\"eventType\":\"%s\",\"schemaVersion\":\"1\"}".formatted(badEventType),
                "{\"orderId\":\"x\"}", clock.instant()));
    }

    private void appendOrders(int count, TraceparentSupplier traceparents) {
        OutboxAppender appender = appender(traceparents);
        for (int i = 0; i < count; i++) {
            appender.append(message(i));
            clock.advance(Duration.ofMillis(10));
        }
        clock.set(NOW);
    }

    private List<OutboxEvent> ordered() {
        return repository.rows().stream()
                .sorted(java.util.Comparator.comparing(OutboxEvent::createdAt).thenComparing(OutboxEvent::id))
                .toList();
    }

    private OutboxAppender appender(TraceparentSupplier traceparents) {
        return new OutboxAppender(repository, json, new Ids(clock, new Random(42)), clock, "order-service",
                traceparents);
    }

    private OutboxBatchPublisher publisher(RecordPublisher recordPublisher, int batchSize) {
        return new OutboxBatchPublisher(repository, recordPublisher, json,
                new TransactionTemplate(transactionManager), clock, "order-service", batchSize,
                Duration.ofSeconds(5));
    }

    private static OutboxMessage message(int index) {
        String orderId = "01a04dad-80da-7f6e-a63a-e91c1035%04d".formatted(index);
        return OutboxMessage.keyedByAggregate("Order", UUID.fromString(orderId), "order.placed", 1,
                new OrderPlaced(orderId));
    }
}
