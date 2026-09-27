package com.dawnline.sim.driver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;

/**
 * 컨슈머 그룹의 랙을 실제 브로커에 묻는다 — {@link KafkaGroupLag} 가 파티션마다 「끝 − 커밋」을 더하고, 커밋이 없는 파티션은
 * 처음부터 센다(ADR-067 후속 「기사는 반영 뒤에 출발한다」).
 *
 * <p>둘째가 요점이다. 커밋이 없는 그룹을 0 으로 읽으면 출발 조건은 tracking 이 아무것도 반영하지 않은 순간에 참이 된다.
 */
class KafkaGroupLagIT {

    private static final String TOPIC = "lag-probe";
    private static final String GROUP = "lag-probe-group";

    private static final KafkaContainer KAFKA = new KafkaContainer(SimDriverIT.KAFKA_IMAGE);

    private static Admin admin;

    @BeforeAll
    static void start() throws ExecutionException, InterruptedException {
        KAFKA.start();
        admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
        admin.createTopics(List.of(new NewTopic(TOPIC, 2, (short) 1))).all().get();
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (KafkaProducer<String, String> producer =
                new KafkaProducer<>(props, new StringSerializer(), new StringSerializer())) {
            for (int i = 0; i < 3; i++) {
                producer.send(new ProducerRecord<>(TOPIC, 0, null, "p0-" + i)).get();
            }
            for (int i = 0; i < 2; i++) {
                producer.send(new ProducerRecord<>(TOPIC, 1, null, "p1-" + i)).get();
            }
        }
    }

    @AfterAll
    static void stop() {
        if (admin != null) {
            admin.close();
        }
        KAFKA.stop();
    }

    @Test
    void 커밋이_없으면_처음부터_세고_커밋만큼_줄고_끝에_닿으면_0_이다() {
        try (KafkaGroupLag lag = new KafkaGroupLag(
                () -> Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers())),
                GROUP, TOPIC)) {
            assertThat(lag.remaining()).as("커밋이 없는 그룹 — 두 파티션의 전부").isEqualTo(5L);

            commit(Map.of(new TopicPartition(TOPIC, 0), 2L));
            assertThat(lag.remaining()).as("p0 은 하나 남고 p1 은 커밋이 없어 전부").isEqualTo(3L);

            commit(Map.of(new TopicPartition(TOPIC, 0), 3L, new TopicPartition(TOPIC, 1), 2L));
            assertThat(lag.remaining()).isZero();
        }
    }

    private static void commit(Map<TopicPartition, Long> offsets) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.assign(offsets.keySet());
            consumer.commitSync(offsets.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                    Map.Entry::getKey, entry -> new OffsetAndMetadata(entry.getValue()))));
        }
    }
}
