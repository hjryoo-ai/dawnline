package com.dawnline.messaging.kafka;

import com.dawnline.messaging.EventHeaders;
import com.dawnline.messaging.outbox.RecordPublisher;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaOperations;

/**
 * {@link RecordPublisher} 의 Spring Kafka 어댑터.
 *
 * <p>key·value 모두 {@code String} 이다. 봉투를 JSON 문자열로 직렬화해 그대로 보내기 때문이다.
 * Spring Kafka 의 {@code JsonSerializer} 를 쓰지 않는 이유: 그 직렬화기는 타입 헤더를 붙이고
 * 자체 매퍼 설정을 가져오는데, 우리 이벤트의 바이트는 {@code contracts/events} 가 정한다.
 * 계약이 정한 바이트를 프레임워크가 다시 손대게 두면 안 된다.
 *
 * <p>따라서 {@code spring.kafka.producer.value-serializer}(및 key)는 Boot 기본값인
 * {@code StringSerializer} 여야 한다. 이 전제가 깨지면 발행이 런타임에 실패한다.
 */
public class KafkaRecordPublisher implements RecordPublisher {

    private final KafkaOperations<String, String> kafka;

    /**
     * @param kafka Spring Kafka 템플릿
     */
    public KafkaRecordPublisher(KafkaOperations<String, String> kafka) {
        this.kafka = Objects.requireNonNull(kafka, "kafka");
    }

    @Override
    public CompletableFuture<Void> publish(String topic, String key, String value, Map<String, String> headers) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
        headers.forEach((name, headerValue) -> record.headers().add(name, EventHeaders.toBytes(headerValue)));
        // thenApply 로 결과를 버린다. 호출자는 "성공했는가" 만 알면 되고,
        // SendResult 를 노출하면 포트가 Spring Kafka 타입에 묶인다.
        return kafka.send(record).thenApply(result -> null);
    }
}
