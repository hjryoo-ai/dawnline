package com.dawnline.ops.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.EventHeaders;
import com.dawnline.ops.application.port.out.DeadLetters;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * 재발행이 무엇을 바꾸고 무엇을 두는가 (DESIGN.md §4.6 「DLQ 재처리」, ADR-053 결정 1·2·4). 브로커는 없다 — 바이트의
 * 동일성은 {@code DlqReplayIT} 가 실제 브로커로 본다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("KafkaDeadLetters — 바이트는 그대로, 헤더는 지목만")
class KafkaDeadLettersTest {

    private static final UUID EVENT = UUID.fromString("0199a000-0000-7000-8000-0000000000e1");
    private static final byte[] KEY = "0199a000-0000-7000-8000-0000000000c1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VALUE = ("{\"eventId\":\"" + EVENT + "\",\"eventType\":\"order.placed\"}")
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void Spring_의_DLQ_헤더와_옛_지목을_빼고_새_지목을_싣고_나머지는_순서대로_둔다() {
        DeadLetters.Raw raw = new DeadLetters.Raw(KEY, VALUE, List.of(
                header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"),
                header("eventType", "order.placed"),
                header("schemaVersion", "1"),
                header(EventHeaders.REPLAY_FOR, "ops-api"),
                header("kafka_dlt-original-topic", "dawnline.order.placed.v1"),
                header("kafka_dlt-exception-message", "주소가 섞였을지도 모르는 메시지")));

        RecordHeaders headers = KafkaDeadLetters.headersFor(raw, "fulfillment-service");

        assertThat(names(headers)).containsExactly("traceparent", "eventType", "schemaVersion",
                EventHeaders.REPLAY_FOR);
        assertThat(new String(headers.lastHeader(EventHeaders.REPLAY_FOR).value(), StandardCharsets.UTF_8))
                .as("다시 재처리하면 지목을 바꾼다 — 두 번 싣지 않는다").isEqualTo("fulfillment-service");
    }

    @Test
    void 키와_value_는_받은_바이트_그대로_원래_토픽과_원래_파티션으로_간다() {
        MockProducer<byte[], byte[]> producer = producer(true);

        assertThat(adapter(producer).republish(letter(), "fulfillment-service"))
                .isInstanceOf(DeadLetters.Delivery.Acked.class);

        ProducerRecord<byte[], byte[]> sent = producer.history().getFirst();
        assertThat(sent.topic()).isEqualTo("dawnline.order.placed.v1");
        assertThat(sent.partition()).isEqualTo(3);
        assertThat(sent.key()).isSameAs(KEY);
        assertThat(sent.value()).as("같은 배열 — 열어서 다시 만든 것이 아니다").isSameAs(VALUE);
        assertThat(sent.timestamp()).as("레코드 시각은 지금이다 — 원래 시각이면 본 토픽 보존이 곧바로 지울 수 있다")
                .isNull();
    }

    @Test
    void 브로커의_재시도_불가_거절은_Refused_이고_모름은_Unknown_이다() {
        MockProducer<byte[], byte[]> refusing = producer(false);
        refusing.sendException = new RecordTooLargeException("too large");
        MockProducer<byte[], byte[]> timingOut = producer(false);
        timingOut.sendException = new TimeoutException("metadata");

        assertThat(adapter(refusing).republish(letter(), "g")).isInstanceOfSatisfying(DeadLetters.Delivery.Refused.class,
                refused -> assertThat(refused.detail()).isEqualTo(RecordTooLargeException.class.getName()));
        assertThat(adapter(timingOut).republish(letter(), "g")).isInstanceOf(DeadLetters.Delivery.Unknown.class);
    }

    @Test
    void ack_를_기다리다_시간이_다_되면_Unknown_이다() {
        MockProducer<byte[], byte[]> silent = producer(false);

        assertThat(adapter(silent).republish(letter(), "g")).as("브로커가 받았는지 모른다")
                .isInstanceOf(DeadLetters.Delivery.Unknown.class);
    }

    @Test
    void value_에서_eventId_를_읽고_못_읽으면_비워_둔다() {
        KafkaDeadLetters adapter = adapter(producer(true));

        assertThat(adapter.eventIdOf(VALUE)).isEqualTo(EVENT);
        assertThat(adapter.eventIdOf("{깨진".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(adapter.eventIdOf("{\"eventId\":7}".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(adapter.eventIdOf("{\"eventId\":\"uuid 아님\"}".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(adapter.eventIdOf(null)).isNull();
    }

    private static KafkaDeadLetters adapter(MockProducer<byte[], byte[]> producer) {
        ProducerFactory<byte[], byte[]> factory = () -> producer;
        return new KafkaDeadLetters(new DefaultKafkaConsumerFactory<>(Map.of()), new KafkaTemplate<>(factory),
                JsonMapper.builder().build(), Duration.ofSeconds(1), Duration.ofMillis(200));
    }

    private static MockProducer<byte[], byte[]> producer(boolean autoComplete) {
        return new MockProducer<>(autoComplete, null, new ByteArraySerializer(), new ByteArraySerializer());
    }

    private static DeadLetters.DeadLetter letter() {
        return new DeadLetters.DeadLetter(3, 17, Instant.EPOCH, "dawnline.order.placed.v1", 3, 5L,
                "fulfillment-service", "order.placed", EVENT, null, new DeadLetters.Raw(KEY, VALUE, List.of()));
    }

    private static DeadLetters.Header header(String name, String value) {
        return new DeadLetters.Header(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> names(RecordHeaders headers) {
        return java.util.Arrays.stream(headers.toArray()).map(Header::key).toList();
    }
}
