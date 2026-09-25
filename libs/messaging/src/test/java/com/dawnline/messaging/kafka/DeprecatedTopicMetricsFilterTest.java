package com.dawnline.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 같은 파티션의 랙이 둘로 보이지 않는다 — KIP-1109 의 폐기 예정 사본을 거른다 (DESIGN.md §9.1 「같은 것이 둘로 보인다」).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DeprecatedTopicMetricsFilterTest {

    private static final String LAG = "kafka.consumer.fetch.manager.records.lag";

    private final MeterRegistry meters = new SimpleMeterRegistry();

    DeprecatedTopicMetricsFilterTest() {
        meters.config().meterFilter(new DeprecatedTopicMetricsFilter());
    }

    @Test
    void 전제__이_저장소의_토픽_이름에는_점이_있다() {
        // 거르는 기준이 이 전제에 기댄다 — 점 없는 토픽이 생기면 그 토픽의 지표가 통째로 사라진다.
        assertThat(Topics.forEvent("order.placed", 1)).contains(".");
        assertThat(Topics.dlqFor(Topics.forEvent("order.placed", 1))).contains(".");
    }

    @Test
    void 파티션_랙은_점_있는_토픽_이름으로_한_번만_남는다() {
        String topic = Topics.forEvent("order.placed", 1);
        meters.gauge(LAG, Tags.of("topic", topic, "partition", "0"), 17.0);
        meters.gauge(LAG, Tags.of("topic", topic.replace('.', '_'), "partition", "0"), 17.0);

        assertThat(meters.find(LAG).gauges()).extracting(gauge -> gauge.getId().getTag("topic"))
                .containsExactly("dawnline.order.placed.v1");
    }

    @Test
    void 토픽_태그가_없는_지표와_Kafka_밖의_지표는_건드리지_않는다() {
        meters.gauge("kafka.consumer.fetch.manager.records.lag.max", Tags.of("client.id", "c-1"), 3.0);
        meters.gauge("dawnline.outbox.lag.seconds", Tags.of("topic", "no_dots_here"), 1.0);

        assertThat(meters.find("kafka.consumer.fetch.manager.records.lag.max").gauges()).hasSize(1);
        assertThat(meters.find("dawnline.outbox.lag.seconds").gauges()).hasSize(1);
    }
}
