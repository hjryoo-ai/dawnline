package com.dawnline.ops.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.error.DomainException;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.common.error.ValidationException;
import com.dawnline.ops.application.port.in.QueryReadModelUseCase;
import com.dawnline.ops.application.port.in.QueryReadModelUseCase.CampKpi;
import com.dawnline.ops.application.port.in.QueryReadModelUseCase.DeliveryKpi;
import com.dawnline.ops.application.port.in.QueryReadModelUseCase.ExceptionList;
import com.dawnline.ops.application.port.in.QueryReadModelUseCase.WaveList;
import com.dawnline.ops.application.port.in.QueryReadModelUseCase.WaveRoutes;
import com.dawnline.ops.application.port.out.CoreQueries;
import com.dawnline.ops.application.port.out.CoreReply;
import com.dawnline.ops.application.port.out.DeliveryKpis;
import com.dawnline.ops.application.port.out.DeliveryKpis.CampDeliveries;
import com.dawnline.ops.application.port.out.ReadModelViews;
import com.dawnline.ops.domain.CoreService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 읽기 모델 조회 (DESIGN.md §5.5 「조회」). SQL 이 실제로 그 행을 고르는지는 {@code ReadSurfaceIT} 가 PostgreSQL 에서 본다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ReadModelQueryServiceTest {

    /** 정시가 아닌 시각 — 버킷을 자르는지가 보인다. */
    private static final Instant NOW = Instant.parse("2026-09-24T10:37:12Z");
    private static final UUID CAMP = UUID.fromString("0199a000-0000-7000-8000-000000000001");
    private static final UUID WAVE = UUID.fromString("0199a000-0000-7000-8000-0000000000a1");
    private static final UUID PLAN = UUID.fromString("0199a000-0000-7000-8000-0000000000b1");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final FakeViews views = new FakeViews();
    private final FakeKpis kpis = new FakeKpis();
    private final FakeCore core = new FakeCore();
    private final ReadModelQueryService service = new ReadModelQueryService(views, kpis, core, clock);

    @Test
    void 웨이브_창을_주지_않으면_지금을_가운데_둔_48시간이다() {
        WaveList list = service.waves(CAMP, null, null);

        assertThat(list.from()).isEqualTo(NOW.minus(Duration.ofHours(24)));
        assertThat(list.to()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(views.wavesLimit).isEqualTo(QueryReadModelUseCase.MAX_WAVES);
    }

    @Test
    void 웨이브_창이_뒤집혔거나_7일을_넘으면_400_이고_읽지_않는다() {
        assertThatThrownBy(() -> service.waves(CAMP, NOW, NOW))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((DomainException) e).details().get("field")).isEqualTo("from");
        assertThatThrownBy(() -> service.waves(CAMP, NOW, NOW.plus(Duration.ofDays(7)).plusSeconds(1)))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((DomainException) e).details().get("field")).isEqualTo("to");
        assertThat(views.wavesLimit).as("거절한 창으로 읽지 않는다").isZero();

        assertThat(service.waves(CAMP, NOW, NOW.plus(Duration.ofDays(7))).waves()).as("7일은 포함한다").isEmpty();
    }

    @Test
    void KPI_는_게이지와_같은_창과_같은_식이다() {
        // 「대시보드의 24행과 게이지가 다를 수 없다」를 API 까지 — 같은 포트·같은 창·같은 식에서 두 수를 뽑아 대조한다.
        kpis.rows.add(new CampDeliveries(CAMP, 90, 10, 81, 88, 7));
        KpiGauges gauges = new KpiGauges(kpis, since -> List.of(), new SimpleMeterRegistry(), clock);
        gauges.refreshNow();
        Instant gaugeFirst = kpis.first;

        DeliveryKpi kpi = service.deliveryKpi();

        assertThat(kpi.firstBucket()).isEqualTo(gaugeFirst).isEqualTo(Instant.parse("2026-09-23T11:00:00Z"));
        assertThat(kpi.lastBucket()).isEqualTo(Instant.parse("2026-09-24T10:00:00Z"));
        CampKpi camp = kpi.camps().getFirst();
        assertThat(camp.onTimeRatioPromised()).isEqualTo(gauges.ratio(CAMP, KpiGauges.Basis.PROMISED))
                .isEqualTo(0.81);
        assertThat(camp.onTimeRatioRevised()).isEqualTo(gauges.ratio(CAMP, KpiGauges.Basis.REVISED))
                .isEqualTo(0.88);
        assertThat(camp.revised()).isEqualTo(7);
    }

    @Test
    void 결과가_없는_캠프의_정시율은_0_이_아니라_null_이다() {
        // 게이지는 NaN 이다. JSON 에는 NaN 이 없고, 0 은 「전부 늦었다」는 주장이다.
        kpis.rows.add(new CampDeliveries(CAMP, 0, 0, 0, 0, 0));
        kpis.withoutPromise = 3;

        DeliveryKpi kpi = service.deliveryKpi();

        assertThat(kpi.camps().getFirst().onTimeRatioPromised()).isNull();
        assertThat(kpi.camps().getFirst().onTimeRatioRevised()).isNull();
        assertThat(kpi.outcomeWithoutPromise()).isEqualTo(3);
    }

    @Test
    void 예외_목록은_상한만큼_싣고_넘치면_전체_수가_목록보다_크다() {
        IntStream.range(0, QueryReadModelUseCase.MAX_EXCEPTIONS + 5).forEach(i -> views.exceptions.add(
                new ReadModelViews.CancelledButDelivered(UUID.randomUUID(), WAVE, null, NOW.minusSeconds(i))));

        ExceptionList list = service.exceptions(CAMP);

        assertThat(views.exceptionLimit).isEqualTo(QueryReadModelUseCase.MAX_EXCEPTIONS);
        assertThat(list.orders()).hasSize(QueryReadModelUseCase.MAX_EXCEPTIONS);
        assertThat(list.total()).as("잘렸다는 사실은 total 이 말한다").isEqualTo(QueryReadModelUseCase.MAX_EXCEPTIONS + 5L);
    }

    @Test
    void 예외_목록이_상한과_같으면_전체_수가_목록과_같다() {
        IntStream.range(0, QueryReadModelUseCase.MAX_EXCEPTIONS).forEach(i -> views.exceptions.add(
                new ReadModelViews.CancelledButDelivered(UUID.randomUUID(), WAVE, null, NOW.minusSeconds(i))));

        ExceptionList list = service.exceptions(CAMP);

        assertThat(list.total()).isEqualTo(list.orders().size());
    }

    @Test
    void 없는_웨이브의_라우트는_404_다() {
        assertThatThrownBy(() -> service.waveRoutes(WAVE)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 계획이_아직_없는_웨이브는_라우트가_비어_있고_라우트를_읽지_않는다() {
        views.plan = new ReadModelViews.WavePlan(WAVE, null, new ReadModelViews.Depot(37.5, 127.0));

        WaveRoutes routes = service.waveRoutes(WAVE);

        assertThat(routes.routes()).isEmpty();
        assertThat(routes.depot()).isEqualTo(new ReadModelViews.Depot(37.5, 127.0));
        assertThat(views.routesAsked).isNull();
    }

    @Test
    void 계획이_있으면_그_계획의_라우트를_읽는다() {
        views.plan = new ReadModelViews.WavePlan(WAVE, PLAN, null);

        WaveRoutes routes = service.waveRoutes(WAVE);

        assertThat(views.routesAsked).isEqualTo(PLAN);
        assertThat(routes.depot()).as("wave.closed 가 아직 오지 않았다 — 부재는 값이 아니다").isNull();
    }

    @Test
    void 라우트_조회_어댑터가_예외를_내면_모름으로_접는다() {
        core.failing = true;

        assertThat(service.route(UUID.randomUUID())).isInstanceOf(CoreReply.Unknown.class);
    }

    private static final class FakeViews implements ReadModelViews {
        final List<CancelledButDelivered> exceptions = new ArrayList<>();
        int wavesLimit;
        int exceptionLimit;
        @Nullable WavePlan plan;
        @Nullable UUID routesAsked;

        @Override
        public List<CampSummary> camps() {
            return List.of();
        }

        @Override
        public List<WaveSummary> waves(UUID campId, Instant from, Instant to, int limit) {
            wavesLimit = limit;
            return List.of();
        }

        @Override
        public Optional<WavePlan> wavePlan(UUID waveId) {
            return Optional.ofNullable(plan);
        }

        @Override
        public List<RouteSummary> routesOf(UUID planId) {
            routesAsked = planId;
            return List.of();
        }

        @Override
        public CancelledButDeliveredPage cancelledButDelivered(UUID campId, int limit) {
            exceptionLimit = limit;
            return new CancelledButDeliveredPage(exceptions.subList(0, Math.min(limit, exceptions.size())),
                    exceptions.size());
        }
    }

    private static final class FakeKpis implements DeliveryKpis {
        final List<CampDeliveries> rows = new ArrayList<>();
        long withoutPromise;
        @Nullable Instant first;

        @Override
        public DeliveryWindow window(Instant firstBucket, Instant lastBucket) {
            first = firstBucket;
            return new DeliveryWindow(rows, withoutPromise);
        }
    }

    private static final class FakeCore implements CoreQueries {
        boolean failing;

        @Override
        public CoreReply listQuarantined(CoreService service, @Nullable Integer limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CoreReply route(UUID routeId) {
            if (failing) {
                throw new IllegalStateException("어댑터 결함");
            }
            return new CoreReply.Unreachable("테스트");
        }
    }
}
