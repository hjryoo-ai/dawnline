package com.dawnline.ops.application;

import com.dawnline.common.error.NotFoundException;
import com.dawnline.common.error.ValidationException;
import com.dawnline.ops.application.port.in.QueryReadModelUseCase;
import com.dawnline.ops.application.port.out.CoreQueries;
import com.dawnline.ops.application.port.out.CoreReply;
import com.dawnline.ops.application.port.out.DeliveryKpis;
import com.dawnline.ops.application.port.out.DeliveryKpis.CampDeliveries;
import com.dawnline.ops.application.port.out.ReadModelViews;
import com.dawnline.ops.application.port.out.ReadModelViews.WavePlan;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 읽기 모델 조회 (DESIGN.md §5.5 「조회」). 감사도 카운터도 없다 — 조회는 커맨드가 아니다.
 *
 * <p>KPI 는 게이지({@link OnTimeRatioGauges})와 같은 세 조각을 쓴다: 창({@link DeliveryKpis#currentBuckets}),
 * 뷰({@link DeliveryKpis#window}), 식({@link CampDeliveries#onTimeRatio}). 같은 사실을 두 경로로 세면 언젠가 갈리고,
 * 갈린 날 어느 쪽을 믿을지 모른다 — 「대시보드의 24행과 게이지가 다를 수 없다」를 API 까지 넓힌 것이다.
 */
public class ReadModelQueryService implements QueryReadModelUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReadModelQueryService.class);

    /** 컷오프 창의 기본 반폭 — 지금을 가운데 둔다. */
    private static final Duration DEFAULT_HALF_SPAN = Duration.ofHours(24);

    private final ReadModelViews views;
    private final DeliveryKpis kpis;
    private final CoreQueries core;
    private final Clock clock;

    /**
     * @param views 읽기 모델 조회
     * @param kpis  배송 축 KPI (게이지와 같은 포트)
     * @param core  코어 조회
     * @param clock 창의 기준 시각 (불변규칙 12)
     */
    public ReadModelQueryService(ReadModelViews views, DeliveryKpis kpis, CoreQueries core, Clock clock) {
        this.views = Objects.requireNonNull(views, "views");
        this.kpis = Objects.requireNonNull(kpis, "kpis");
        this.core = Objects.requireNonNull(core, "core");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CampList camps() {
        return new CampList(views.camps());
    }

    @Override
    public WaveList waves(UUID campId, @Nullable Instant from, @Nullable Instant to) {
        Instant now = clock.instant();
        Instant start = from == null ? now.minus(DEFAULT_HALF_SPAN) : from;
        Instant end = to == null ? now.plus(DEFAULT_HALF_SPAN) : to;
        if (!start.isBefore(end)) {
            throw new ValidationException("컷오프 창의 시작이 끝보다 앞서야 한다", Map.of("field", "from"));
        }
        if (Duration.between(start, end).compareTo(MAX_WAVE_SPAN) > 0) {
            throw new ValidationException("컷오프 창은 7일을 넘을 수 없다", Map.of("field", "to"));
        }
        return new WaveList(campId, start, end, views.waves(campId, start, end, MAX_WAVES));
    }

    @Override
    public DeliveryKpi deliveryKpi() {
        DeliveryKpis.Buckets buckets = DeliveryKpis.currentBuckets(clock.instant());
        DeliveryKpis.DeliveryWindow window = kpis.window(buckets.first(), buckets.last());
        List<CampKpi> camps = window.camps().stream()
                .map(camp -> new CampKpi(camp.campId(), camp.delivered(), camp.failed(), camp.onTimePromised(),
                        camp.onTimeRevised(), camp.revised(), known(camp.onTimeRatio(true)),
                        known(camp.onTimeRatio(false))))
                .toList();
        return new DeliveryKpi(buckets.first(), buckets.last(), camps, window.outcomeWithoutPromise());
    }

    @Override
    public ExceptionList exceptions(UUID campId) {
        ReadModelViews.CancelledButDeliveredPage page = views.cancelledButDelivered(campId, MAX_EXCEPTIONS);
        return new ExceptionList(campId, page.orders(), page.total());
    }

    @Override
    public WaveRoutes waveRoutes(UUID waveId) {
        WavePlan plan = views.wavePlan(waveId).orElseThrow(() -> new NotFoundException(
                "읽기 모델에 없는 웨이브다 — 아직 사실이 오지 않았거나 없는 id 다", Map.of("waveId", waveId.toString())));
        return new WaveRoutes(waveId, plan.planId(), plan.depot(),
                plan.planId() == null ? List.of() : views.routesOf(plan.planId()));
    }

    /** 포트는 예외를 던지지 않는 것이 계약이다. 새어 나오면 {@link QuarantineQueryService} 와 같이 「모름」으로 접는다. */
    @Override
    public CoreReply route(UUID routeId) {
        try {
            return core.route(routeId);
        } catch (RuntimeException e) {
            log.error("라우트 조회 어댑터가 예외를 냈다 routeId={}", routeId, e);
            return new CoreReply.Unknown(false, null, e.toString());
        }
    }

    /** 게이지의 {@code NaN} 은 JSON 에 없다 — 모름은 {@code null} 이다. 0 은 「전부 늦었다」는 주장이다. */
    private static @Nullable Double known(double ratio) {
        return Double.isNaN(ratio) ? null : ratio;
    }
}
