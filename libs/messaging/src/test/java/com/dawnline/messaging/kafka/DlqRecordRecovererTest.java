package com.dawnline.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.EventHeaders;
import com.dawnline.messaging.MessagingMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;

/** {@link DlqRecordRecoverer} — DLQ 발행에 붙는 로그와 메트릭 (DESIGN.md §4.6, §9.1). */
class DlqRecordRecovererTest {

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final List<ConsumerRecord<?, ?>> recovered = new ArrayList<>();
    private final ConsumerAwareRecordRecoverer delegate =
            (record, consumer, exception) -> recovered.add(record);

    @Test
    void accept_위임하고_dlq_메트릭을_올린다() {
        new DlqRecordRecoverer(delegate, meters, "order-service")
                .accept(record("order.placed"), null, new IllegalStateException("깨진 메시지"));

        assertThat(recovered).hasSize(1);
        assertThat(counter("order.placed")).isEqualTo(1.0);
    }

    @Test
    void accept_eventType_헤더가_없으면_unknown으로_집계한다() {
        // 역직렬화조차 실패한 메시지에도 통해야 한다. 라벨 카디널리티가 터지지 않게 고정값을 쓴다.
        new DlqRecordRecoverer(delegate, meters, "order-service")
                .accept(record(null), null, new IllegalStateException("헤더 없음"));

        assertThat(counter(MessagingMetrics.UNKNOWN)).isEqualTo(1.0);
    }

    private double counter(String eventType) {
        return meters.get(MessagingMetrics.EVENT_PROCESSED)
                .tag(MessagingMetrics.TAG_CONSUMER, "order-service")
                .tag(MessagingMetrics.TAG_EVENT_TYPE, eventType)
                .tag(MessagingMetrics.TAG_OUTCOME, MessagingMetrics.OUTCOME_DLQ)
                .counter().count();
    }

    private static ConsumerRecord<String, String> record(String eventType) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("dawnline.order.placed.v1", 3, 42L, "o-1", "{}");
        if (eventType != null) {
            record.headers().add(EventHeaders.EVENT_TYPE, eventType.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
