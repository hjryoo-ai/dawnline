package com.dawnline.fulfillment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.fulfillment.domain.FcFallbackReason;
import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.WaveCloseCause;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** fulfillment 고유 메트릭 (§9.1). */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FulfillmentMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FulfillmentMetrics metrics = new FulfillmentMetrics(registry);

    @Test
    void 개정과_대체를_캠프_단위로_센다() {
        metrics.promiseRevised("CAMP-SEO-C", ServiceTier.SAME_DAY, WaveCloseCause.SCHEDULED);
        metrics.promiseRevised("CAMP-SEO-C", ServiceTier.SAME_DAY, WaveCloseCause.SCHEDULED);
        metrics.fcFallback("CAMP-GYG-N", FcFallbackReason.COLD);

        assertThat(registry.get(FulfillmentMetrics.PROMISE_REVISED)
                .tag("camp", "CAMP-SEO-C").tag("tier", "SAME_DAY").counter().count()).isEqualTo(2);
        assertThat(registry.get(FulfillmentMetrics.FC_FALLBACK)
                .tag("camp", "CAMP-GYG-N").tag("reason", "cold").counter().count()).isEqualTo(1);
    }

    @Test
    void 게이지는_같은_캠프_티어에_대해_값을_갱신한다() {
        // registry.gauge 는 (이름, 라벨)이 같으면 기존 미터를 돌려주고 새 값을 반영하지 않는다.
        // AtomicInteger 를 들고 있어야 하는 이유이고, 이 테스트가 그것을 못 박는다.
        metrics.waveClosed("CAMP-SEO-C", ServiceTier.DAWN, 4820);
        metrics.waveClosed("CAMP-SEO-C", ServiceTier.DAWN, 5100);

        assertThat(registry.get(FulfillmentMetrics.WAVE_ORDERS)
                .tag("camp", "CAMP-SEO-C").tag("tier", "DAWN").gauge().value()).isEqualTo(5100);
        assertThat(registry.find(FulfillmentMetrics.WAVE_ORDERS).gauges()).hasSize(1);
    }

    @Test
    void 캠프_티어마다_게이지가_따로_생긴다() {
        metrics.waveClosed("CAMP-SEO-C", ServiceTier.DAWN, 10);
        metrics.waveClosed("CAMP-SEO-C", ServiceTier.SAME_DAY, 20);
        metrics.waveClosed("CAMP-GYG-N", ServiceTier.DAWN, 30);

        assertThat(registry.find(FulfillmentMetrics.WAVE_ORDERS).gauges()).hasSize(3);
    }

    @Test
    void 라벨_키_집합이_메트릭마다_일정하다() {
        // Prometheus 는 같은 이름의 미터가 같은 라벨 키 집합을 갖기를 요구한다(§9.1).
        // 원인을 모르는 개정도 같은 키 집합으로 센다 — 라벨을 빼면 그 시계열만 키가 달라진다.
        metrics.promiseRevised("CAMP-A", ServiceTier.DAWN, WaveCloseCause.SCHEDULED);
        metrics.promiseRevised("CAMP-B", ServiceTier.NEXT_DAY, WaveCloseCause.MANUAL);
        metrics.promiseRevised("CAMP-C", ServiceTier.SAME_DAY, null);

        assertThat(registry.find(FulfillmentMetrics.PROMISE_REVISED).counters())
                .hasSize(3)
                .allSatisfy(counter -> assertThat(counter.getId().getTags())
                        .extracting(Tag::getKey).containsExactlyInAnyOrder("camp", "tier", "cause"));
    }

    @Test
    void 개정의_원인은_소문자_라벨_둘과_모름_하나다() {
        // ADR-054 결정 4 — scheduled 는 grace 로 흡수하지 못한 지연, manual 은 운영자가 앞당긴 컷오프의 대가.
        // 둘을 한 값으로 세면 사람이 누른 결과가 「grace 가 모자라다」로 읽힌다.
        metrics.promiseRevised("CAMP-A", ServiceTier.DAWN, WaveCloseCause.SCHEDULED);
        metrics.promiseRevised("CAMP-A", ServiceTier.DAWN, WaveCloseCause.MANUAL);
        metrics.promiseRevised("CAMP-A", ServiceTier.DAWN, WaveCloseCause.MANUAL);
        metrics.promiseRevised("CAMP-A", ServiceTier.DAWN, null);

        assertThat(revised("scheduled")).isEqualTo(1);
        assertThat(revised("manual")).isEqualTo(2);
        assertThat(revised(FulfillmentMetrics.CAUSE_UNKNOWN)).isEqualTo(1);
    }

    private double revised(String cause) {
        return registry.get(FulfillmentMetrics.PROMISE_REVISED)
                .tag("camp", "CAMP-A").tag("tier", "DAWN").tag("cause", cause).counter().count();
    }
}
