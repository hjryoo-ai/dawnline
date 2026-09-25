package com.dawnline.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.MessagingMetrics;
import com.dawnline.observability.DawnlineMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** outbox 게이지 (DESIGN.md §9.1). */
class OutboxMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final OutboxMetrics metrics = new OutboxMetrics(registry, "order-service");

    @Test
    void 생성자_게이지_세_개를_service_태그와_함께_등록한다() {
        assertThat(gauge(DawnlineMetrics.OUTBOX_UNPUBLISHED.meterName())).isZero();
        assertThat(gauge(DawnlineMetrics.OUTBOX_LAG_SECONDS.meterName())).isZero();
        assertThat(gauge(DawnlineMetrics.OUTBOX_FAILED.meterName())).isZero();
    }

    @Test
    void refresh_값을_갱신한다() {
        metrics.refresh(42L, 3.5, 2L);

        assertThat(gauge(DawnlineMetrics.OUTBOX_UNPUBLISHED.meterName())).isEqualTo(42.0);
        assertThat(gauge(DawnlineMetrics.OUTBOX_LAG_SECONDS.meterName())).isEqualTo(3.5);
        assertThat(gauge(DawnlineMetrics.OUTBOX_FAILED.meterName())).isEqualTo(2.0);
    }

    @Test
    void refresh_격리_행은_미발행과_따로_센다() {
        // 두 게이지가 같은 행을 세면 "미발행 0건인데 지연은 계속 올라간다" 같은 모순이 대시보드에 나온다.
        metrics.refresh(0L, 0.0, 3L);

        assertThat(gauge(DawnlineMetrics.OUTBOX_UNPUBLISHED.meterName())).isZero();
        assertThat(gauge(DawnlineMetrics.OUTBOX_FAILED.meterName())).isEqualTo(3.0);
    }

    @Test
    void refresh_밀리초_단위까지_보존한다() {
        metrics.refresh(1L, 0.123, 0L);

        assertThat(gauge(DawnlineMetrics.OUTBOX_LAG_SECONDS.meterName())).isEqualTo(0.123);
    }

    @Test
    void refresh_미발행이_사라지면_0으로_돌아온다() {
        metrics.refresh(10L, 30.0, 1L);
        metrics.refresh(0L, 0.0, 0L);

        assertThat(gauge(DawnlineMetrics.OUTBOX_UNPUBLISHED.meterName())).isZero();
        assertThat(gauge(DawnlineMetrics.OUTBOX_LAG_SECONDS.meterName())).isZero();
        assertThat(gauge(DawnlineMetrics.OUTBOX_FAILED.meterName())).isZero();
    }

    private double gauge(String name) {
        return registry.get(name).tag(MessagingMetrics.TAG_SERVICE, "order-service").gauge().value();
    }
}
