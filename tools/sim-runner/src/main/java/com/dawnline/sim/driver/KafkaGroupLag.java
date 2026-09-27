package com.dawnline.sim.driver;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.jspecify.annotations.Nullable;

/**
 * 컨슈머 그룹의 랙을 브로커에 묻는다 — 파티션마다 {@code 끝 오프셋 − 커밋 오프셋}의 합.
 *
 * <p>커밋이 없는 파티션은 <strong>처음부터</strong> 센다(가장 이른 오프셋). 그 그룹이 아직 그 파티션을 한 번도 커밋하지 않았다는 것은
 * 반영한 것이 없다는 뜻이지 밀린 것이 없다는 뜻이 아니다.
 *
 * <p>{@link Admin} 은 처음 물을 때 만든다 — 창 시나리오가 아니면 이 빈은 한 번도 불리지 않고, 그 실행이 브로커 연결을 열 이유가 없다.
 */
public final class KafkaGroupLag implements ApplyLag, AutoCloseable {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

    private final Supplier<Admin> adminFactory;
    private final String group;
    private final String topic;

    private @Nullable Admin admin;

    /**
     * @param adminFactory Admin 을 만든다 — 처음 물을 때 한 번
     * @param group        잴 컨슈머 그룹 (tracking 의 {@code spring.kafka.consumer.group-id})
     * @param topic        토픽
     */
    public KafkaGroupLag(Supplier<Admin> adminFactory, String group, String topic) {
        this.adminFactory = Objects.requireNonNull(adminFactory, "adminFactory");
        this.group = Objects.requireNonNull(group, "group");
        this.topic = Objects.requireNonNull(topic, "topic");
    }

    @Override
    public synchronized long remaining() {
        Admin client = admin();
        Set<TopicPartition> partitions = get(client.describeTopics(List.of(topic)).allTopicNames()).get(topic)
                .partitions().stream().map(info -> new TopicPartition(topic, info.partition()))
                .collect(Collectors.toUnmodifiableSet());
        Map<TopicPartition, OffsetAndMetadata> committed =
                get(client.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata());
        Map<TopicPartition, ListOffsetsResultInfo> latest = offsets(client, partitions, OffsetSpec.latest());
        Map<TopicPartition, ListOffsetsResultInfo> earliest = offsets(client, partitions, OffsetSpec.earliest());

        long lag = 0L;
        for (TopicPartition partition : partitions) {
            OffsetAndMetadata commit = committed.get(partition);
            long from = Math.max(earliest.get(partition).offset(), commit == null ? 0L : commit.offset());
            lag += Math.max(0L, latest.get(partition).offset() - from);
        }
        return lag;
    }

    private static Map<TopicPartition, ListOffsetsResultInfo> offsets(Admin client, Set<TopicPartition> partitions,
            OffsetSpec spec) {
        return get(client.listOffsets(partitions.stream()
                .collect(Collectors.toMap(Function.identity(), partition -> spec))).all());
    }

    private Admin admin() {
        if (admin == null) {
            admin = adminFactory.get();
        }
        return admin;
    }

    private static <T> T get(KafkaFuture<T> future) {
        try {
            return future.get(CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("컨슈머 그룹의 랙을 묻다 중단되었다", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("컨슈머 그룹의 랙을 브로커에 묻지 못했다", e);
        }
    }

    @Override
    public synchronized void close() {
        if (admin != null) {
            admin.close(CALL_TIMEOUT);
        }
    }
}
