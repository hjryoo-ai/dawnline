package com.dawnline.ops.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.ops.application.OnTimeRatioGauges.Basis;
import com.dawnline.ops.application.port.out.DeliveryKpis;
import com.dawnline.ops.application.port.out.DeliveryKpis.CampDeliveries;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OnTimeRatioGaugesTest {

    /** 창의 기준 — 정시가 아닌 시각이라 버킷을 자르는지가 보인다. */
    private static final Instant NOW = Instant.parse("2026-09-24T10:37:12Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID CAMP = UUID.fromString("0199a000-0000-7000-8000-000000000001");

    private final FakeKpis kpis = new FakeKpis();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OnTimeRatioGauges gauges = new OnTimeRatioGauges(kpis, registry, CLOCK);

    @Test
    void 창은_지금_버킷을_포함한_UTC_정시_버킷_24개다() {
        gauges.refreshNow();

        assertThat(kpis.first).isEqualTo(Instant.parse("2026-09-23T11:00:00Z"));
        assertThat(kpis.last).isEqualTo(Instant.parse("2026-09-24T10:00:00Z"));
    }

    @Test
    void 분모는_완료와_실패다_실패를_빼면_정시율이_오른다() {
        // 완료 90 (원 약속 정시 81, 개정 약속 정시 88) · 실패 10.
        kpis.rows.add(new CampDeliveries(CAMP, 90, 10, 81, 88));
        gauges.refreshNow();

        assertThat(value(Basis.PROMISED)).isEqualTo(0.81);
        assertThat(value(Basis.REVISED)).isEqualTo(0.88);
    }

    @Test
    void 캠프를_처음_볼_때_두_기준을_등록한다() {
        assertThat(registry.find(OnTimeRatioGauges.ON_TIME_RATIO).gauges()).as("아직 본 캠프가 없다").isEmpty();

        kpis.rows.add(new CampDeliveries(CAMP, 1, 0, 1, 1));
        gauges.refreshNow();
        gauges.refreshNow();

        assertThat(registry.find(OnTimeRatioGauges.ON_TIME_RATIO).tag("camp", CAMP.toString()).gauges())
                .extracting(g -> g.getId().getTag("basis"))
                .containsExactlyInAnyOrder("promised", "revised");
    }

    @Test
    void 창에서_사라진_캠프는_0_이_아니라_NaN_이다() {
        kpis.rows.add(new CampDeliveries(CAMP, 10, 0, 10, 10));
        gauges.refreshNow();
        assertThat(value(Basis.PROMISED)).isEqualTo(1.0);

        kpis.rows.clear();
        gauges.refreshNow();

        // 0 은 「전부 늦었다」, 마지막 값은 「아직 그렇다」 — 둘 다 아무도 하지 않은 주장이다.
        assertThat(value(Basis.PROMISED)).isNaN();
        assertThat(value(Basis.REVISED)).isNaN();
    }

    @Test
    void 갱신이_실패하면_멈춘_값이_아니라_NaN_이다() {
        kpis.rows.add(new CampDeliveries(CAMP, 10, 0, 9, 10));
        gauges.refreshNow();
        assertThat(value(Basis.PROMISED)).isEqualTo(0.9);

        kpis.failing = true;
        gauges.refresh();

        assertThat(value(Basis.PROMISED)).isNaN();
        assertThatThrownBy(gauges::refreshNow).as("수동 실행은 삼키지 않는다").isInstanceOf(IllegalStateException.class);
    }

    private double value(Basis basis) {
        Gauge gauge = registry.get(OnTimeRatioGauges.ON_TIME_RATIO)
                .tag("camp", CAMP.toString()).tag("basis", basis.name().toLowerCase(java.util.Locale.ROOT)).gauge();
        return gauge.value();
    }

    private static final class FakeKpis implements DeliveryKpis {
        final List<CampDeliveries> rows = new ArrayList<>();
        Instant first;
        Instant last;
        boolean failing;

        @Override
        public List<CampDeliveries> sumByCamp(Instant firstBucket, Instant lastBucket) {
            if (failing) {
                throw new IllegalStateException("DB 가 없다");
            }
            first = firstBucket;
            last = lastBucket;
            return List.copyOf(rows);
        }
    }
}
