package com.dawnline.messaging.kafka;

import com.dawnline.messaging.EventHeaders;
import com.dawnline.messaging.MessagingMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

/**
 * DLQ 발행에 메트릭과 로그를 덧붙이는 얇은 래퍼 (DESIGN.md §4.6, §9.1).
 *
 * <p>{@link DeadLetterPublishingRecoverer} 가 실제 발행을 하고, 여기서는
 * {@code dawnline_event_processed_total{outcome="dlq"}} 를 올린다. §9.1 의 {@code outcome} 라벨에
 * {@code dlq} 가 있는데, 그 결정은 리스너 바깥(에러 핸들러)에서 나므로
 * {@code IdempotentConsumer} 가 관측할 수 없다. 그래서 여기에 둔다.
 *
 * <p>{@link ConsumerAwareRecordRecoverer} 를 구현해 {@code Consumer} 참조를 그대로 위임한다.
 * 단순 {@code BiConsumer} 로 감싸면 DLQ 발행이 컨슈머 컨텍스트를 잃는다.
 */
public class DlqRecordRecoverer implements ConsumerAwareRecordRecoverer {

    private static final Logger log = LoggerFactory.getLogger(DlqRecordRecoverer.class);

    private final ConsumerAwareRecordRecoverer delegate;
    private final MeterRegistry meters;
    private final String consumer;

    /**
     * @param delegate 실제 DLQ 발행기. 프로덕션에서는 {@link DeadLetterPublishingRecoverer} 다.
     *                 인터페이스로 받는 이유는 이 클래스의 책임(로그·메트릭)을 브로커 없이 테스트하기 위해서다.
     * @param meters   Micrometer 레지스트리
     * @param consumer {@code consumer} 태그 값 (보통 서비스 이름)
     */
    public DlqRecordRecoverer(ConsumerAwareRecordRecoverer delegate, MeterRegistry meters, String consumer) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.meters = Objects.requireNonNull(meters, "meters");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> kafkaConsumer, Exception exception) {
        String eventType = eventTypeOf(record);
        // 개인정보가 섞일 수 있는 value 는 로그에 남기지 않는다 (§9.3). 위치와 타입만 남긴다.
        log.error("DLQ 로 보냅니다. topic={}, partition={}, offset={}, eventType={}",
                record.topic(), record.partition(), record.offset(), eventType, exception);
        delegate.accept(record, kafkaConsumer, exception);
        Counter.builder(MessagingMetrics.EVENT_PROCESSED)
                .description("이벤트 소비 결과")
                .tag(MessagingMetrics.TAG_CONSUMER, consumer)
                .tag(MessagingMetrics.TAG_EVENT_TYPE, eventType)
                .tag(MessagingMetrics.TAG_OUTCOME, MessagingMetrics.OUTCOME_DLQ)
                .register(meters)
                .increment();
    }

    /**
     * 헤더에서 이벤트 타입을 읽는다. 헤더에 중복 기록해 둔 덕분에 <strong>페이로드를 파싱하지 않고</strong>
     * 타입을 알 수 있다 (§4.2). 역직렬화조차 실패한 메시지에도 통한다.
     */
    private static String eventTypeOf(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader(EventHeaders.EVENT_TYPE);
        if (header == null || header.value() == null) {
            return MessagingMetrics.UNKNOWN;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
