package com.dawnline.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.EventHeaders;
import com.dawnline.messaging.MessagingMetrics;
import com.dawnline.observability.DawnlineMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaUtils;

/** 지목되지 않은 재처리만 버린다 (DESIGN.md §4.6, ADR-053 결정 2). */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("ReplayTargetFilter — 재처리는 지목된 그룹의 것이다")
class ReplayTargetFilterTest {

    private static final String TOPIC = "dawnline.order.placed.v1";

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @AfterEach
    void clearGroup() {
        KafkaUtils.clearConsumerGroupId();
    }

    @Test
    void 재처리가_아니면_버리지_않는다() {
        assertThat(filter("ops-api").filter(record(null))).isFalse();
        assertThat(notTarget("ops-api")).isZero();
    }

    @Test
    void 자기를_지목한_재처리는_버리지_않는다() {
        assertThat(filter("ops-api").filter(record("ops-api"))).isFalse();
        assertThat(notTarget("ops-api")).isZero();
    }

    @Test
    void 다른_그룹을_지목한_재처리는_버리고_replay_not_target_으로_센다() {
        assertThat(filter("ops-api").filter(record("fulfillment-service"))).isTrue();

        assertThat(notTarget("ops-api")).isEqualTo(1.0);
        assertThat(meters.find(DawnlineMetrics.EVENT_PROCESSED.meterName())
                .tag(MessagingMetrics.TAG_OUTCOME, MessagingMetrics.OUTCOME_DUP).counter())
                .as("dup 과 섞지 않는다 — 「이미 처리했다」와 「내 일이 아니다」는 다른 사실이다").isNull();
    }

    @Test
    void 자기_그룹을_모르면_처리한다() {
        // 필터가 없던 때의 동작으로 돌아간다 — 대상일지도 모르는 그룹에서 재처리를 잃지 않는다.
        assertThat(filter(null).filter(record("fulfillment-service"))).isFalse();
    }

    @Test
    void 기본_생성자는_컨테이너가_알려_주는_그룹과_비교한다() {
        ReplayTargetFilter filter = new ReplayTargetFilter(meters);

        KafkaUtils.setConsumerGroupId("fulfillment-service");
        assertThat(filter.filter(record("fulfillment-service"))).isFalse();
        KafkaUtils.setConsumerGroupId("ops-api");
        assertThat(filter.filter(record("fulfillment-service"))).isTrue();
    }

    private ReplayTargetFilter filter(@Nullable String group) {
        return new ReplayTargetFilter(meters, () -> group);
    }

    private double notTarget(String consumer) {
        Counter counter = meters.find(DawnlineMetrics.EVENT_PROCESSED.meterName())
                .tag(MessagingMetrics.TAG_CONSUMER, consumer)
                .tag(MessagingMetrics.TAG_EVENT_TYPE, "order.placed")
                .tag(MessagingMetrics.TAG_OUTCOME, MessagingMetrics.OUTCOME_REPLAY_NOT_TARGET)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private static ConsumerRecord<Object, Object> record(@Nullable String replayFor) {
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>(TOPIC, 0, 0L, "key", "value");
        record.headers().add(EventHeaders.EVENT_TYPE, EventHeaders.toBytes("order.placed"));
        if (replayFor != null) {
            record.headers().add(EventHeaders.REPLAY_FOR, EventHeaders.toBytes(replayFor));
        }
        return record;
    }
}
