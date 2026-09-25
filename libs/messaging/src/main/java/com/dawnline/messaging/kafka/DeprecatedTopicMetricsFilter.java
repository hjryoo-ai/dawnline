package com.dawnline.messaging.kafka;

import com.dawnline.messaging.Topics;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;

/**
 * Kafka 클라이언트가 파티션 · 토픽 지표를 <strong>두 번</strong> 내는 것을 하나로 (DESIGN.md §9.1 「같은 것이 둘로 보인다」).
 *
 * <p>Kafka 클라이언트 4.x(KIP-1109)는 토픽 이름에 점이 있으면 같은 지표를 {@code topic} 태그의 점을 밑줄로 바꾼 <em>폐기 예정</em> 사본과
 * 함께 기록한다({@code FetchMetricsManager.shouldReportDeprecatedMetric} — 이름에 {@code "."} 이 있으면 참). 이 저장소의 토픽은 전부
 * 점이 있다({@link Topics#forEvent} — {@code dawnline.<type>.v<n>}). 그래서 {@code kafka_consumer_fetch_manager_records_lag} 가 파티션마다
 * {@code dawnline.order.placed.v1} 과 {@code dawnline_order_placed_v1} 두 시계열이었고, 합하면 두 배였다(근거: 관측(재현됨) —
 * 2026-09-25 로컬 Prometheus, 서비스마다 시계열 수가 정확히 두 배). 등록 경로가 둘인 것이 아니라 클라이언트가 일부러 둘을 낸다.
 *
 * <p>거르는 기준: Kafka 클라이언트 지표({@code kafka.} 로 시작)의 {@code topic} 태그에 점이 <strong>없으면</strong> 폐기 예정 사본이다.
 * 이 기준은 「우리 토픽 이름에는 반드시 점이 있다」에 기대고, 그 전제는 {@code DeprecatedTopicMetricsFilterTest} 가 {@link Topics} 로 본다.
 */
public final class DeprecatedTopicMetricsFilter implements MeterFilter {

    private static final String TOPIC_TAG = "topic";

    @Override
    public MeterFilterReply accept(Meter.Id id) {
        if (!id.getName().startsWith("kafka.")) {
            return MeterFilterReply.NEUTRAL;
        }
        String topic = id.getTag(TOPIC_TAG);
        return topic != null && !topic.contains(".") ? MeterFilterReply.DENY : MeterFilterReply.NEUTRAL;
    }
}
