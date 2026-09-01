package com.dawnline.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.MessagingMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** outbox 게이지 (DESIGN.md §9.1). */
class OutboxMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final OutboxMetrics metrics = new OutboxMetrics(registry, "order-service");

    @Test
    void 생성자_게이지_두_개를_service_태그와_함께_등록한다() {
        assertThat(gauge(MessagingMetrics.OUTBOX_UNPUBLISHED)).isZero();
        assertThat(gauge(MessagingMetrics.OUTBOX_LAG_SECONDS)).isZero();
    }

    @Test
    void refresh_값을_갱신한다() {
        metrics.refresh(42L, 3.5);

        assertThat(gauge(MessagingMetrics.OUTBOX_UNPUBLISHED)).isEqualTo(42.0);
        assertThat(gauge(MessagingMetrics.OUTBOX_LAG_SECONDS)).isEqualTo(3.5);
    }

    @Test
    void refresh_밀리초_단위까지_보존한다() {
        metrics.refresh(1L, 0.123);

        assertThat(gauge(MessagingMetrics.OUTBOX_LAG_SECONDS)).isEqualTo(0.123);
    }

    @Test
    void refresh_미발행이_사라지면_0으로_돌아온다() {
        metrics.refresh(10L, 30.0);
        metrics.refresh(0L, 0.0);

        assertThat(gauge(MessagingMetrics.OUTBOX_UNPUBLISHED)).isZero();
        assertThat(gauge(MessagingMetrics.OUTBOX_LAG_SECONDS)).isZero();
    }

    private double gauge(String name) {
        return registry.get(name).tag(MessagingMetrics.TAG_SERVICE, "order-service").gauge().value();
    }
}
