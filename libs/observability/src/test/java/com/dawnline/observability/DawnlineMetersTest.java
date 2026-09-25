package com.dawnline.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 등록 헬퍼 — 카탈로그와 대조하는 자리이고, 게이지를 강한 참조로 잡는 자리다 (ADR-060 결정 2).
 */
class DawnlineMetersTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void 타입이_항목과_다르면_등록에서_실패한다() {
        assertThatThrownBy(() -> DawnlineMeters.gauge(registry, DawnlineMetrics.CANCEL_TOO_LATE, new AtomicLong(),
                AtomicLong::doubleValue, "camp", "c"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dawnline_cancel_too_late_total 는 counter 다");
        assertThatThrownBy(() -> DawnlineMeters.counter(registry, DawnlineMetrics.PLAN_PERSIST, "camp", "c"))
                .hasMessageContaining("histogram");
    }

    @Test
    void 라벨_키_집합이_항목과_다르면_첫_등록에서_실패한다() {
        // Prometheus 레지스트리도 거부하지만 그것은 같은 이름의 둘째 등록에서다 — 첫 등록은 조용히 지나간다.
        assertThatThrownBy(() -> DawnlineMeters.counter(registry, DawnlineMetrics.CANCEL_TOO_LATE))
                .hasMessageContaining("[camp]");
        assertThatThrownBy(() -> DawnlineMeters.counter(registry, DawnlineMetrics.CANCEL_TOO_LATE,
                "camp", "c", "tier", "DAWN"))
                .hasMessageContaining("라벨 키");
        assertThat(registry.getMeters()).as("실패한 등록은 아무것도 남기지 않는다").isEmpty();
    }

    @Test
    void 닫힌_라벨에_목록_밖의_값이면_실패한다() {
        assertThatThrownBy(() -> DawnlineMeters.counter(registry, DawnlineMetrics.REPLAN, "outcome", "no_gain"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("목록 밖의 값 \"no_gain\"");
    }

    @Test
    void 열린_라벨은_어떤_값이든_받는다() {
        DawnlineMeters.counter(registry, DawnlineMetrics.CANCEL_TOO_LATE, "camp", "아무 캠프").increment();

        assertThat(registry.get(DawnlineMetrics.CANCEL_TOO_LATE.meterName()).tag("camp", "아무 캠프").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void 태그가_짝이_아니면_실패한다() {
        assertThatThrownBy(() -> DawnlineMeters.counter(registry, DawnlineMetrics.CANCEL_TOO_LATE, "camp"))
                .hasMessageContaining("key, value 쌍");
    }

    @Test
    void histogram_항목은_버킷과_함께_등록된다() {
        // 속성 파일의 키로 켜던 때는 키가 미터 이름과 달라 버킷이 없었다(ADR-060 맥락 1) — 이제 타입 칸이 켠다.
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        DawnlineMeters.timer(prometheus, DawnlineMetrics.PLAN_DURATION,
                "strategy", "s", "mode", "FULL", "termination", "converged").record(Duration.ofSeconds(3));

        assertThat(prometheus.scrape()).contains("dawnline_plan_duration_seconds_bucket{");
    }

    @Test
    void 게이지는_상태를_강하게_잡는다_GC_뒤에도_NaN_이_아니다() {
        // 전제: 이 JVM 에서 GC 가 약한 참조를 실제로 거둔다. 대조군은 Micrometer 의 기본(약한 참조)으로 등록한다 —
        // 거두지 않는 JVM 이면 아래 검사는 강한 참조가 아니어도 통과하므로, 먼저 그 사실을 세운다(§13 축 10 의 규칙).
        WeakReference<AtomicLong> control = weaklyRegistered();
        WeakReference<AtomicLong> held = stronglyRegistered();

        for (int i = 0; i < 50 && control.get() != null; i++) {
            System.gc();
            sleep();
        }

        assertThat(control.get()).as("전제 — 약한 참조로 등록한 대조군을 GC 가 거뒀다").isNull();
        assertThat(registry.get("probe.weak").gauge().value()).as("전제 — 대상을 잃은 게이지는 NaN").isNaN();

        assertThat(held.get()).as("헬퍼로 등록한 상태는 레지스트리가 잡고 있다").isNotNull();
        assertThat(registry.get(DawnlineMetrics.ROUTE_PLANS_STUCK.meterName()).gauge().value()).isEqualTo(7.0);
    }

    @Test
    void 미리_등록은_닫힌_라벨의_조합_전부를_0_으로_만든다() {
        int registered = DawnlineMeters.preregister(registry, DawnlineMetrics.OPS_COMMANDS);

        assertThat(registered).isEqualTo(6 * 4);
        assertThat(registry.get(DawnlineMetrics.OPS_COMMANDS.meterName())
                .tag("action", "CLOSE_WAVE").tag("result", "UNKNOWN").counter().count()).isZero();
    }

    @Test
    void 미리_등록은_고정한_라벨을_그_값으로_둔다() {
        assertThat(DawnlineMeters.preregister(registry, DawnlineMetrics.OPS_COMMANDS, "action", "DLQ_REPLAY"))
                .isEqualTo(4);
        assertThat(registry.find(DawnlineMetrics.OPS_COMMANDS.meterName()).tag("action", "RUN_PLAN").counters())
                .isEmpty();
    }

    @Test
    void 열린_라벨은_미리_등록할_수_없다() {
        assertThatThrownBy(() -> DawnlineMeters.preregister(registry, DawnlineMetrics.CANCEL_TOO_LATE))
                .hasMessageContaining("열려 있어 미리 등록할 수 없다");
    }

    private WeakReference<AtomicLong> weaklyRegistered() {
        AtomicLong state = new AtomicLong(3);
        Gauge.builder("probe.weak", state, AtomicLong::doubleValue).register(registry);
        return new WeakReference<>(state);
    }

    private WeakReference<AtomicLong> stronglyRegistered() {
        AtomicLong state = new AtomicLong(7);
        DawnlineMeters.gauge(registry, DawnlineMetrics.ROUTE_PLANS_STUCK, state, AtomicLong::doubleValue);
        return new WeakReference<>(state);
    }

    private static void sleep() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
