package com.dawnline.ops.adapter.out.messaging;

import com.dawnline.messaging.EventHeaders;
import com.dawnline.messaging.Topics;
import com.dawnline.messaging.outbox.PublishFailureClassifier;
import com.dawnline.ops.application.port.out.DeadLetters;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.jspecify.annotations.Nullable;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * DLQ 를 {@code byte[]} 로 읽고 {@code byte[]} 로 다시 보낸다 (DESIGN.md §4.6 「DLQ 재처리」, ADR-053 결정 1).
 *
 * <h2>객체로 읽는 경로가 없다</h2>
 * 키·value·헤더는 바이트로 읽어 바이트로 보낸다. value 를 여는 곳은 {@link #eventIdOf} 하나이고, 거기서 얻는 것은
 * 감사 행에 적을 {@code eventId} 뿐이다 — 연 결과로 다시 쓰지 않는다. 그래서 「재처리한 이벤트의 {@code eventId} 가
 * 원래와 같다」는 주장이 아니라 바이트의 동일성이다.
 *
 * <h2>무엇을 바꾸는가</h2>
 * <ul>
 *   <li>헤더: Spring 이 DLQ 적재 때 붙인 {@code kafka_dlt-*} 를 빼고, {@link EventHeaders#REPLAY_FOR} 를 (있으면 바꿔)
 *       싣는다. 나머지({@code traceparent}·{@code eventType}·{@code schemaVersion})는 그대로.</li>
 *   <li>레코드 시각: 비워 두어 지금 시각이 된다. 이벤트의 시각은 봉투의 {@code occurredAt} 이고 소비자는 레코드
 *       시각을 읽지 않는다 — 원래 시각을 지니면 본 토픽의 시간 보존(7일)이 재처리한 레코드를 곧바로 지울 수 있다.</li>
 * </ul>
 *
 * <h2>이 발행에 outbox 가 없는 이유</h2>
 * 도메인 상태를 바꾸지 않는 재전송이고, <strong>이 발행의 상태는 감사 행이다</strong>(ADR-053 결정 3). 부르는 쪽이
 * {@code PENDING} 을 커밋한 뒤에만 {@link #republish} 를 부른다.
 *
 * <p>프로듀서·컨슈머는 이 어댑터만의 것이다 — {@code KafkaTemplate} 빈을 하나 더 두면 Boot 의 기본 템플릿이
 * 물러나고({@code @ConditionalOnMissingBean}) DLQ 복구기와 outbox 릴레이가 그것을 잃는다.
 */
public class KafkaDeadLetters implements DeadLetters, AutoCloseable {

    /** Spring 이 DLQ 적재 때 붙이는 헤더의 접두어. 다시 보낼 때 뺀다. */
    static final String DLT_HEADER_PREFIX = "kafka_dlt-";

    private static final Duration POLL = Duration.ofMillis(200);

    private final ConsumerFactory<byte[], byte[]> consumers;
    private final KafkaTemplate<byte[], byte[]> producer;
    private final JsonMapper json;
    private final PublishFailureClassifier classifier = new PublishFailureClassifier();
    private final Duration readTimeout;
    private final Duration sendTimeout;

    /**
     * @param consumers   그룹 없는 {@code byte[]} 컨슈머 — {@code assign} 으로만 쓰고 오프셋을 커밋하지 않는다
     * @param producer    {@code byte[]} 프로듀서
     * @param json        {@code eventId} 를 읽는 데만 쓴다
     * @param readTimeout 한 번 읽기의 상한
     * @param sendTimeout ack 를 기다리는 상한 — 넘으면 {@code UNKNOWN}
     */
    public KafkaDeadLetters(ConsumerFactory<byte[], byte[]> consumers, KafkaTemplate<byte[], byte[]> producer,
            JsonMapper json, Duration readTimeout, Duration sendTimeout) {
        this.consumers = Objects.requireNonNull(consumers, "consumers");
        this.producer = Objects.requireNonNull(producer, "producer");
        this.json = Objects.requireNonNull(json, "json");
        this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout");
        this.sendTimeout = Objects.requireNonNull(sendTimeout, "sendTimeout");
    }

    @Override
    public List<DeadLetter> peek(String topic, int limit) {
        String dlq = Topics.dlqFor(topic);
        try (Consumer<byte[], byte[]> consumer = consumers.createConsumer()) {
            List<TopicPartition> partitions = partitions(consumer, dlq);
            if (partitions.isEmpty()) {
                return List.of();
            }
            consumer.assign(partitions);
            Map<TopicPartition, Long> begin = consumer.beginningOffsets(partitions, readTimeout);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions, readTimeout);
            Map<TopicPartition, Long> remaining = new HashMap<>();
            for (TopicPartition partition : partitions) {
                long start = Math.max(begin.get(partition), end.get(partition) - limit);
                consumer.seek(partition, start);
                if (start < end.get(partition)) {
                    remaining.put(partition, end.get(partition));
                }
            }
            List<DeadLetter> letters = new ArrayList<>();
            long deadline = System.nanoTime() + readTimeout.toNanos();
            while (!remaining.isEmpty() && System.nanoTime() < deadline) {
                for (ConsumerRecord<byte[], byte[]> record : consumer.poll(POLL)) {
                    TopicPartition partition = new TopicPartition(record.topic(), record.partition());
                    Long stop = remaining.get(partition);
                    if (stop != null && record.offset() < stop) {
                        letters.add(letterOf(record));
                    }
                }
                remaining.keySet().removeIf(partition -> consumer.position(partition) >= remaining.get(partition));
            }
            return letters.stream()
                    .sorted(Comparator.comparing(DeadLetter::timestamp).reversed()
                            .thenComparing(DeadLetter::partition).thenComparing(DeadLetter::offset))
                    .limit(limit)
                    .toList();
        }
    }

    @Override
    public Optional<DeadLetter> read(String topic, int partition, long offset) {
        String dlq = Topics.dlqFor(topic);
        try (Consumer<byte[], byte[]> consumer = consumers.createConsumer()) {
            TopicPartition target = new TopicPartition(dlq, partition);
            if (!partitions(consumer, dlq).contains(target)) {
                return Optional.empty();
            }
            consumer.assign(List.of(target));
            long begin = consumer.beginningOffsets(List.of(target), readTimeout).get(target);
            long end = consumer.endOffsets(List.of(target), readTimeout).get(target);
            if (offset < begin || offset >= end) {
                return Optional.empty();
            }
            consumer.seek(target, offset);
            long deadline = System.nanoTime() + readTimeout.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<byte[], byte[]> record : consumer.poll(POLL)) {
                    if (record.offset() == offset) {
                        return Optional.of(letterOf(record));
                    }
                    if (record.offset() > offset) {
                        return Optional.empty();
                    }
                }
            }
            throw new IllegalStateException("DLQ 레코드를 " + readTimeout + " 안에 읽지 못했다: " + target + "@" + offset);
        }
    }

    @Override
    public Delivery republish(DeadLetter letter, String targetGroup) {
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(
                Objects.requireNonNull(letter.originalTopic(), "originalTopic"),
                Objects.requireNonNull(letter.originalPartition(), "originalPartition"),
                null, letter.raw().key(), letter.raw().value(), headersFor(letter.raw(), targetGroup));
        try {
            producer.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Delivery.Acked();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Delivery.Unknown(e.getClass().getName());
        } catch (Exception e) {
            return switch (classifier.classify(PublishFailureClassifier.Phase.DELIVERY, e)) {
                case DETERMINISTIC -> new Delivery.Refused(rootClass(e));
                case TRANSIENT -> new Delivery.Unknown(rootClass(e));
            };
        }
    }

    /** 원래 헤더에서 {@code kafka_dlt-*} 와 옛 지목을 빼고, 새 지목을 싣는다. */
    static RecordHeaders headersFor(Raw raw, String targetGroup) {
        RecordHeaders headers = new RecordHeaders();
        for (Header header : raw.headers()) {
            if (header.name().startsWith(DLT_HEADER_PREFIX) || header.name().equals(EventHeaders.REPLAY_FOR)) {
                continue;
            }
            headers.add(new RecordHeader(header.name(), header.value()));
        }
        headers.add(EventHeaders.REPLAY_FOR, EventHeaders.toBytes(targetGroup));
        return headers;
    }

    @Override
    public void close() {
        producer.getProducerFactory().reset();
    }

    private List<TopicPartition> partitions(Consumer<byte[], byte[]> consumer, String topic) {
        List<PartitionInfo> infos = consumer.partitionsFor(topic, readTimeout);
        if (infos == null) {
            return List.of();
        }
        return infos.stream().map(info -> new TopicPartition(info.topic(), info.partition())).toList();
    }

    private DeadLetter letterOf(ConsumerRecord<byte[], byte[]> record) {
        List<Header> headers = new ArrayList<>();
        record.headers().forEach(header -> headers.add(new Header(header.key(), header.value())));
        return new DeadLetter(record.partition(), record.offset(), Instant.ofEpochMilli(record.timestamp()),
                string(record, KafkaHeaders.DLT_ORIGINAL_TOPIC),
                integer(record, KafkaHeaders.DLT_ORIGINAL_PARTITION),
                longValue(record, KafkaHeaders.DLT_ORIGINAL_OFFSET),
                string(record, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP),
                string(record, EventHeaders.EVENT_TYPE),
                eventIdOf(record.value()),
                string(record, KafkaHeaders.DLT_EXCEPTION_FQCN),
                new Raw(record.key(), record.value(), headers));
    }

    /** 봉투의 {@code eventId} — 감사 행에 적으려고만 읽는다. 읽지 못하면 비어 있다(깨진 바이트도 재처리 대상이다). */
    @Nullable UUID eventIdOf(byte @Nullable [] value) {
        if (value == null) {
            return null;
        }
        try {
            JsonNode eventId = json.readTree(value).get("eventId");
            return eventId == null || !eventId.isString() ? null : UUID.fromString(eventId.asString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static @Nullable String string(ConsumerRecord<?, ?> record, String name) {
        byte @Nullable [] value = last(record, name);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    /** Spring 은 원래 파티션을 4바이트 정수로 적는다. 모양이 다르면 모른다고 답한다. */
    private static @Nullable Integer integer(ConsumerRecord<?, ?> record, String name) {
        byte @Nullable [] value = last(record, name);
        return value == null || value.length != Integer.BYTES ? null : ByteBuffer.wrap(value).getInt();
    }

    /** Spring 은 원래 오프셋을 8바이트 정수로 적는다. */
    private static @Nullable Long longValue(ConsumerRecord<?, ?> record, String name) {
        byte @Nullable [] value = last(record, name);
        return value == null || value.length != Long.BYTES ? null : ByteBuffer.wrap(value).getLong();
    }

    private static byte @Nullable [] last(ConsumerRecord<?, ?> record, String name) {
        org.apache.kafka.common.header.Header header = record.headers().lastHeader(name);
        return header == null ? null : header.value();
    }

    private static String rootClass(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getName();
    }
}
