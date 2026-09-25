package com.dawnline.ops.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.observability.DawnlineMetrics;
import com.dawnline.ops.application.KpiGauges.Basis;
import com.dawnline.ops.application.port.out.DeliveryKpis;
import com.dawnline.ops.application.port.out.DeliveryKpis.CampDeliveries;
import com.dawnline.ops.application.port.out.DeliveryKpis.DeliveryWindow;
import com.dawnline.ops.application.port.out.RouteCounts;
import com.dawnline.ops.application.port.out.RouteCounts.CampRoutes;
import com.dawnline.ops.domain.DeliveryOutcome;
import com.dawnline.ops.domain.RouteProgress;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class KpiGaugesTest {

    /** 창의 기준 — 정시가 아닌 시각이라 버킷을 자르는지가 보인다. */
    private static final Instant NOW = Instant.parse("2026-09-24T10:37:12Z");
    private static final UUID CAMP = UUID.fromString("0199a000-0000-7000-8000-000000000001");

    private final MovingClock clock = new MovingClock(NOW);
    private final FakeKpis kpis = new FakeKpis();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FakeRoutes routes = new FakeRoutes();
    private final KpiGauges gauges = new KpiGauges(kpis, routes, registry, clock);

    @Test
    void 창은_지금_버킷을_포함한_UTC_정시_버킷_24개다() {
        gauges.refreshNow();

        assertThat(kpis.first).isEqualTo(Instant.parse("2026-09-23T11:00:00Z"));
        assertThat(kpis.last).isEqualTo(Instant.parse("2026-09-24T10:00:00Z"));
    }

    @Test
    void 분모는_완료와_실패다_실패를_빼면_정시율이_오른다() {
        // 완료 90 (원 약속 정시 81, 개정 약속 정시 88) · 실패 10.
        kpis.rows.add(new CampDeliveries(CAMP, 90, 10, 81, 88, 0));
        gauges.refreshNow();

        assertThat(value(Basis.PROMISED)).isEqualTo(0.81);
        assertThat(value(Basis.REVISED)).isEqualTo(0.88);
    }

    @Test
    void 캠프를_처음_볼_때_두_기준을_등록한다() {
        assertThat(registry.find(DawnlineMetrics.DELIVERY_ON_TIME_RATIO.meterName()).gauges()).as("아직 본 캠프가 없다").isEmpty();

        kpis.rows.add(new CampDeliveries(CAMP, 1, 0, 1, 1, 0));
        gauges.refreshNow();
        gauges.refreshNow();

        assertThat(registry.find(DawnlineMetrics.DELIVERY_ON_TIME_RATIO.meterName()).tag("camp", CAMP.toString()).gauges())
                .extracting(g -> g.getId().getTag("basis"))
                .containsExactlyInAnyOrder("promised", "revised");
    }

    @Test
    void 창에서_사라진_캠프는_0_이_아니라_NaN_이다() {
        kpis.rows.add(new CampDeliveries(CAMP, 10, 0, 10, 10, 0));
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
        kpis.rows.add(new CampDeliveries(CAMP, 10, 0, 9, 10, 0));
        gauges.refreshNow();
        assertThat(value(Basis.PROMISED)).isEqualTo(0.9);

        kpis.failing = true;
        gauges.refresh();

        assertThat(value(Basis.PROMISED)).isNaN();
        assertThatThrownBy(gauges::refreshNow).as("수동 실행은 삼키지 않는다").isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 약속을_모르는_결과는_정시율에서_빠지고_그_수가_보인다() {
        kpis.rows.add(new CampDeliveries(CAMP, 9, 1, 9, 9, 0));
        kpis.withoutPromise = 3; // 캠프를 아는 행과 모르는 행의 합 — 어댑터가 더한다
        gauges.refreshNow();

        assertThat(value(Basis.PROMISED)).as("분모는 결과·약속을 아는 10건").isEqualTo(0.9);
        assertThat(excluded()).isEqualTo(3.0);
    }

    @Test
    void 빠진_수도_모르면_0_이_아니라_NaN_이다() {
        assertThat(excluded()).as("아직 세지 않았다").isNaN();

        gauges.refreshNow();
        assertThat(excluded()).isZero();

        kpis.failing = true;
        gauges.refresh();
        assertThat(excluded()).as("갱신 실패 — 0 이면 「빠진 것이 없다」는 주장이 된다").isNaN();
    }

    @Test
    void 갱신_나이는_마지막_성공부터_스크레이프마다_커진다() {
        clock.advance(Duration.ofSeconds(30));
        assertThat(age()).as("성공한 적이 없으면 기동부터 센다 — 처음부터 죽은 갱신도 조용하지 않게").isEqualTo(30.0);

        gauges.refreshNow();
        assertThat(age()).isZero();

        kpis.failing = true;
        clock.advance(Duration.ofSeconds(61));
        gauges.refresh();
        clock.advance(Duration.ofSeconds(61));
        gauges.refresh();

        // 정시율은 NaN 이라 `< 0.95` 알림이 울리지 않는다 — 이 값이 대신 커진다.
        assertThat(age()).isEqualTo(122.0);
    }

    @Test
    void 결과_수는_정시율과_같은_스냅숏이고_창에_결과가_없는_캠프는_0_이다() {
        kpis.rows.add(new CampDeliveries(CAMP, 90, 10, 81, 88, 0));
        gauges.refreshNow();

        assertThat(deliveries(DeliveryOutcome.COMPLETED)).isEqualTo(90.0);
        assertThat(deliveries(DeliveryOutcome.FAILED)).as("정시율의 분모 100 을 편 것").isEqualTo(10.0);

        kpis.rows.clear();
        gauges.refreshNow();

        // 정시율은 0/0 이라 NaN 이지만 「결과 0 건」은 참인 셈이다.
        assertThat(value(Basis.PROMISED)).isNaN();
        assertThat(deliveries(DeliveryOutcome.COMPLETED)).isZero();
        assertThat(deliveries(DeliveryOutcome.FAILED)).isZero();
    }

    @Test
    void 결과_수도_갱신이_실패하면_0_이_아니라_NaN_이다() {
        kpis.rows.add(new CampDeliveries(CAMP, 5, 1, 5, 5, 0));
        gauges.refreshNow();

        kpis.failing = true;
        gauges.refresh();

        assertThat(deliveries(DeliveryOutcome.COMPLETED)).isNaN();
        assertThat(deliveries(DeliveryOutcome.FAILED)).isNaN();
    }

    @Test
    void 라우트는_캠프와_진행으로_세고_창은_정시율의_첫_버킷이다() {
        routes.rows.add(new CampRoutes(CAMP, RouteProgress.ASSIGNED, 3));
        routes.rows.add(new CampRoutes(CAMP, RouteProgress.IN_PROGRESS, 5));
        routes.rows.add(new CampRoutes(null, RouteProgress.UNKNOWN, 2));
        gauges.refreshNow();

        assertThat(routes.since).isEqualTo(kpis.first);
        assertThat(routeGauge(CAMP.toString(), RouteProgress.ASSIGNED)).isEqualTo(3.0);
        assertThat(routeGauge(CAMP.toString(), RouteProgress.IN_PROGRESS)).isEqualTo(5.0);
        assertThat(routeGauge(CAMP.toString(), RouteProgress.COMPLETED)).as("없는 조합 — 센 결과 0").isZero();
        assertThat(routeGauge(CAMP.toString(), RouteProgress.UNKNOWN)).isZero();
        assertThat(routeGauge("unknown", RouteProgress.UNKNOWN)).as("캠프를 모르는 행도 빠지지 않는다").isEqualTo(2.0);
    }

    @Test
    void 캠프를_처음_볼_때_진행_다섯을_등록한다_라우트_단위가_아니다() {
        routes.rows.add(new CampRoutes(CAMP, RouteProgress.COMPLETED, 1));
        gauges.refreshNow();
        gauges.refreshNow();

        assertThat(registry.find(DawnlineMetrics.ROUTES.meterName()).gauges())
                .extracting(g -> g.getId().getTag("camp") + " " + g.getId().getTag("status"))
                .containsExactlyInAnyOrder(CAMP + " assigned", CAMP + " in_progress", CAMP + " completed",
                        CAMP + " void", CAMP + " unknown");
    }

    @Test
    void 라우트_집계가_실패하면_갱신_전체가_실패다() {
        kpis.rows.add(new CampDeliveries(CAMP, 10, 0, 10, 10, 0));
        routes.rows.add(new CampRoutes(CAMP, RouteProgress.ASSIGNED, 1));
        gauges.refreshNow();
        clock.advance(Duration.ofSeconds(90));

        routes.failing = true;
        gauges.refresh();

        // 성공 시각이 하나라서 갱신 나이가 셋 모두의 알림이다 — 정시율만 멀쩡해 보이는 반쪽 성공이 없다.
        assertThat(routeGauge(CAMP.toString(), RouteProgress.ASSIGNED)).isNaN();
        assertThat(value(Basis.PROMISED)).isNaN();
        assertThat(age()).isEqualTo(90.0);
    }

    private double deliveries(DeliveryOutcome outcome) {
        return registry.get(DawnlineMetrics.KPI_DELIVERY.meterName()).tag("camp", CAMP.toString())
                .tag("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT)).gauge().value();
    }

    private double routeGauge(String camp, RouteProgress progress) {
        return registry.get(DawnlineMetrics.ROUTES.meterName()).tag("camp", camp).tag("status", progress.label())
                .gauge().value();
    }

    private double excluded() {
        return registry.get(DawnlineMetrics.KPI_EXCLUDED.meterName()).tag("reason", "promise_unknown").gauge().value();
    }

    private double age() {
        return registry.get(DawnlineMetrics.KPI_REFRESH_AGE.meterName()).gauge().value();
    }

    private double value(Basis basis) {
        Gauge gauge = registry.get(DawnlineMetrics.DELIVERY_ON_TIME_RATIO.meterName())
                .tag("camp", CAMP.toString()).tag("basis", basis.name().toLowerCase(java.util.Locale.ROOT)).gauge();
        return gauge.value();
    }

    private static final class FakeKpis implements DeliveryKpis {
        final List<CampDeliveries> rows = new ArrayList<>();
        long withoutPromise;
        Instant first;
        Instant last;
        boolean failing;

        @Override
        public DeliveryWindow window(Instant firstBucket, Instant lastBucket) {
            if (failing) {
                throw new IllegalStateException("DB 가 없다");
            }
            first = firstBucket;
            last = lastBucket;
            return new DeliveryWindow(rows, withoutPromise);
        }
    }

    private static final class FakeRoutes implements RouteCounts {
        final List<CampRoutes> rows = new ArrayList<>();
        Instant since;
        boolean failing;

        @Override
        public List<CampRoutes> count(Instant firstBucket) {
            if (failing) {
                throw new IllegalStateException("DB 가 없다");
            }
            since = firstBucket;
            return List.copyOf(rows);
        }
    }

    private static final class MovingClock extends Clock {
        private Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }
}
