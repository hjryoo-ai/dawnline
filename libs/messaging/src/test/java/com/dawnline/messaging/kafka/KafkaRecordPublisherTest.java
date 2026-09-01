package com.dawnline.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.EventHeaders;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/** {@link KafkaRecordPublisher} — 포트를 Spring Kafka 레코드로 옮기는 어댑터. */
class KafkaRecordPublisherTest {

    @Test
    void publish_토픽_키_값_헤더를_그대로_옮긴다() {
        CapturingKafkaTemplate template = new CapturingKafkaTemplate();

        CompletableFuture<Void> result = new KafkaRecordPublisher(template).publish(
                "dawnline.order.placed.v1", "o-1", "{\"eventId\":\"x\"}",
                Map.of(EventHeaders.EVENT_TYPE, "order.placed", EventHeaders.SCHEMA_VERSION, "1"));

        assertThat(result).isCompleted();
        ProducerRecord<String, String> sent = template.captured;
        assertThat(sent).isNotNull();
        assertThat(sent.topic()).isEqualTo("dawnline.order.placed.v1");
        assertThat(sent.key()).isEqualTo("o-1");
        assertThat(sent.value()).isEqualTo("{\"eventId\":\"x\"}");
        assertThat(header(sent, EventHeaders.EVENT_TYPE)).isEqualTo("order.placed");
        assertThat(header(sent, EventHeaders.SCHEMA_VERSION)).isEqualTo("1");
    }

    @Test
    void publish_전송_실패는_future로_전달된다() {
        CapturingKafkaTemplate template = new CapturingKafkaTemplate();
        template.failure = new IllegalStateException("브로커 없음");

        CompletableFuture<Void> result =
                new KafkaRecordPublisher(template).publish("t", "k", "v", Map.of());

        assertThat(result).isCompletedExceptionally();
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** 브로커에 연결하지 않고 보낸 레코드만 붙잡는 템플릿. */
    private static final class CapturingKafkaTemplate extends KafkaTemplate<String, String> {

        private ProducerRecord<String, String> captured;
        private RuntimeException failure;

        private CapturingKafkaTemplate() {
            super(new DefaultKafkaProducerFactory<>(producerConfigs(), new StringSerializer(),
                    new StringSerializer()));
        }

        private static Map<String, Object> producerConfigs() {
            Map<String, Object> configs = new HashMap<>();
            configs.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
            return configs;
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(ProducerRecord<String, String> record) {
            captured = record;
            return failure == null ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(failure);
        }
    }
}
