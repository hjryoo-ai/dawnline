package com.dawnline.messaging.kafka;

import com.dawnline.messaging.EventHeaders;
import com.dawnline.messaging.MessagingMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.adapter.RecordFilterStrategy;
import org.springframework.kafka.support.KafkaUtils;

/**
 * 다른 그룹을 지목한 DLQ 재처리를 리스너 <strong>앞에서</strong> 건너뛴다 (DESIGN.md §4.6, ADR-053).
 *
 * <p>DLQ 레코드는 이벤트의 실패가 아니라 소비자 그룹 하나의 실패다. ops-api 는 그 그룹을
 * {@link EventHeaders#REPLAY_FOR} 로 지목해 원래 토픽에 다시 보내고, 그 토픽의 다른 그룹들은 이 필터에서
 * 레코드를 버린다 — 리스너가 불리지 않으므로 {@code IdempotentConsumer} 도 불리지 않는다.
 *
 * <h2>왜 멱등 게이트 앞인가</h2>
 * 건너뛴 레코드는 {@code processed_events} 에 <strong>적지 않는다.</strong> 그룹 X 가 {@code replay-for=Y}
 * 레코드를 건너뛰며 {@code (eventId, X)} 를 적으면, 나중에 같은 이벤트를 X 대상으로 재처리할 때
 * {@code dup} 으로 막힌다. 게이트 앞에 서 있으면 그 테이블을 만질 길이 처음부터 없다. 그리고 다섯 서비스의
 * 리스너가 헤더를 옮겨 적을 필요도 없다 — Boot 가 이 빈을 기본 리스너 컨테이너 팩토리에 꽂는다.
 *
 * <h2>흔적은 카운터 하나</h2>
 * {@code dawnline_event_processed_total{outcome="replay_not_target"}}. {@code dup} 과 섞지 않는다.
 * 건너뛰기에는 커밋할 상태가 없다 — 오프셋을 커밋하기 전에 죽으면 다시 받아 다시 세고, 그것은 재전달이
 * {@code dup} 을 두 번 세는 것과 같은 부류다.
 *
 * <h2>자기 그룹을 모르면</h2>
 * 처리한다(버리지 않는다). 컨테이너 스레드에서는 그룹 id 가 늘 있으므로 실제로는 오지 않는 갈래다. 그래도
 * 고른다면 대상일지도 모르는 그룹에서 재처리를 잃는 것보다, 필터가 없던 때의 동작(모든 그룹이 받고
 * {@code processed_events} 가 거른다)으로 돌아가는 편이 낫다 — 잃은 재처리는 감사 행이 {@code SUCCEEDED} 라
 * 아무도 모른다.
 */
public class ReplayTargetFilter implements RecordFilterStrategy<Object, Object> {

    private static final Logger log = LoggerFactory.getLogger(ReplayTargetFilter.class);

    private final MeterRegistry meters;
    private final Supplier<@Nullable String> currentGroup;

    /**
     * 컨테이너가 알려 주는 그룹 id({@link KafkaUtils#getConsumerGroupId()})와 비교한다 — Spring 이 DLQ 레코드의
     * {@code kafka_dlt-original-consumer-group} 에 적는 값과 같은 출처다.
     *
     * @param meters Micrometer 레지스트리
     */
    public ReplayTargetFilter(MeterRegistry meters) {
        this(meters, KafkaUtils::getConsumerGroupId);
    }

    /**
     * @param meters       Micrometer 레지스트리
     * @param currentGroup 지금 이 레코드를 받은 소비자 그룹
     */
    ReplayTargetFilter(MeterRegistry meters, Supplier<@Nullable String> currentGroup) {
        this.meters = Objects.requireNonNull(meters, "meters");
        this.currentGroup = Objects.requireNonNull(currentGroup, "currentGroup");
    }

    /**
     * @return 버리면 {@code true} — 다른 그룹을 지목한 재처리일 때만
     */
    @Override
    public boolean filter(ConsumerRecord<Object, Object> record) {
        String target = header(record, EventHeaders.REPLAY_FOR);
        if (target == null) {
            return false;
        }
        String group = currentGroup.get();
        if (group == null) {
            log.warn("자기 그룹을 몰라 재처리를 처리한다 — 지목된 그룹={}, topic={}, partition={}, offset={}",
                    target, record.topic(), record.partition(), record.offset());
            return false;
        }
        if (group.equals(target)) {
            return false;
        }
        String eventType = header(record, EventHeaders.EVENT_TYPE);
        Counter.builder(MessagingMetrics.EVENT_PROCESSED)
                .description("이벤트 소비 결과")
                .tag(MessagingMetrics.TAG_CONSUMER, group)
                .tag(MessagingMetrics.TAG_EVENT_TYPE, eventType == null ? MessagingMetrics.UNKNOWN : eventType)
                .tag(MessagingMetrics.TAG_OUTCOME, MessagingMetrics.OUTCOME_REPLAY_NOT_TARGET)
                .register(meters)
                .increment();
        log.debug("다른 그룹의 재처리라 건너뛴다 — 지목된 그룹={}, topic={}, partition={}, offset={}",
                target, record.topic(), record.partition(), record.offset());
        return true;
    }

    private static @Nullable String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) {
            return null;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
