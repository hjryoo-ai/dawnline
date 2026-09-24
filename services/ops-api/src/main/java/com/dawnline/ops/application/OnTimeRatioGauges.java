package com.dawnline.ops.application;

import com.dawnline.ops.application.port.out.DeliveryKpis;
import com.dawnline.ops.application.port.out.DeliveryKpis.CampDeliveries;
import com.dawnline.ops.application.port.out.DeliveryKpis.DeliveryWindow;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * {@code dawnline_delivery_on_time_ratio{camp, basis}} — 정시율 두 값을 따로 낸다 (DESIGN.md §9.1 · §8.1).
 * 그리고 그 값이 정직한지를 말하는 둘을 함께 낸다: 정시율에서 빠진 수({@code dawnline_kpi_excluded})와
 * 마지막 성공한 갱신의 나이({@code dawnline_kpi_refresh_age_seconds}).
 *
 * <h2>창은 현재 버킷 포함 UTC 정시 버킷 24개다</h2>
 * 「직전 24시간」이 아니다 — {@code kpi_delivery_hourly} 의 행 24개의 합이고, 현재 버킷은 늘 부분이라
 * 창의 길이는 23시간 남짓에서 24시간 사이를 움직인다. 게이지가 뷰를 읽으므로 대시보드의 24행과
 * 게이지가 다른 수를 말할 수 없다 — 같은 사실을 두 경로로 세면 언젠가 갈리고, 갈린 날 어느 쪽을
 * 믿을지 모른다.
 *
 * <h2>분모는 결과가 난 주문이다 — 실패를 포함한다</h2>
 * {@code 정시 / (완료 + 실패)}. 실패는 분모에 있고 분자에 없다 — 실패를 빼면 정시율이 오른다.
 * 취소·배차 불가는 뷰가 이미 뺐다.
 *
 * <h2>모름은 값이 아니다 — 그러나 모름의 수는 값이다</h2>
 * 창 안에 결과가 없는 캠프, 그리고 갱신이 실패한 동안의 모든 캠프는 {@code NaN} 이다 — 0 도 아니고
 * 마지막 값도 아니다. 0 은 「전부 늦었다」는 주장이고, 멈춘 값은 건강해 보인다
 * ({@code dawnline_shipment_partitions_ahead} 와 같은 이유).
 *
 * <p>약속을 아직 모르는 결과는 정시율의 분모에도 분자에도 없다. 조용히 빠지면 실패를 빼서 정시율을
 * 올리는 것과 같은 부류이므로 그 수를 {@code dawnline_kpi_excluded{reason="promise_unknown"}} 로 낸다
 * — 정상에서는 프로젝션 랙만큼의 일시값이고, 계속 0 이 아니면 {@code fulfillment.planned} 가 오지 않고
 * 있다.
 *
 * <h2>NaN 은 정직하지만 아무도 못 듣는다</h2>
 * Prometheus 의 {@code < 0.95} 는 {@code NaN} 에 대해 거짓이라 갱신이 죽으면 정시율 알림이 조용해진다.
 * 그래서 {@code dawnline_kpi_refresh_age_seconds} 가 마지막 <em>성공한</em> 갱신 뒤로 흐른 시간을
 * 스크레이프할 때마다 계산한다 — 갱신이 멈추면 값이 멈추지 않고 커진다. 성공한 적이 없으면 이 객체가
 * 만들어진 때(기동)부터 센다: 처음부터 죽은 갱신도 조용하지 않게.
 *
 * <h2>미터는 캠프를 처음 볼 때 등록한다</h2>
 * 캠프 목록은 ops 의 사실이 아니다(fulfillment 의 표다). 창에 처음 나타난 캠프의 두 계열을 그때
 * 등록하고, 사라져도 지우지 않는다 — 그 뒤로는 {@code NaN} 을 말한다.
 */
public class OnTimeRatioGauges {

    /** §9.1 의 이름 — Prometheus 에서 {@code dawnline_delivery_on_time_ratio}. */
    public static final String ON_TIME_RATIO = "dawnline.delivery.on.time.ratio";

    /** §9.1 — Prometheus 에서 {@code dawnline_kpi_excluded}. */
    public static final String EXCLUDED = "dawnline.kpi.excluded";

    /** §9.1 — Prometheus 에서 {@code dawnline_kpi_refresh_age_seconds}. */
    public static final String REFRESH_AGE = "dawnline.kpi.refresh.age.seconds";

    static final String TAG_CAMP = "camp";
    static final String TAG_BASIS = "basis";
    static final String TAG_REASON = "reason";

    /** {@code reason} 라벨 — 결과는 났는데 약속(또는 캠프)을 아직 모른다. */
    static final String PROMISE_UNKNOWN = "promise_unknown";

    /** 창의 버킷 수 — 지금 버킷을 포함한다. */
    static final int BUCKETS = 24;

    /** {@code basis} 라벨의 값 — 뷰의 칸 이름 {@code on_time_promised}·{@code on_time_revised} 와 맞춘다. */
    public enum Basis {
        /** 고객이 처음 받은 약속 — SLO 의 기준(§8.1). */
        PROMISED,
        /** 개정된 약속 — 참고값. */
        REVISED;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(OnTimeRatioGauges.class);

    private final DeliveryKpis kpis;
    private final MeterRegistry registry;
    private final Clock clock;
    private final Set<UUID> registered = ConcurrentHashMap.newKeySet();

    /** 마지막으로 센 창. {@code null} 은 모름 — 아직 세지 않았거나 마지막 갱신이 실패했다. */
    private volatile @Nullable Snapshot latest;

    /** 마지막으로 성공한 갱신 — 성공한 적이 없으면 기동 시각. */
    private volatile Instant lastSuccess;

    /**
     * 빠진 수와 갱신 나이는 여기서 등록한다 — 캠프와 무관하고, 갱신이 한 번도 성공하지 못해도 있어야 한다.
     *
     * @param kpis     배송 축 KPI
     * @param registry 미터 레지스트리
     * @param clock    창의 기준 시각 (불변규칙 12)
     */
    public OnTimeRatioGauges(DeliveryKpis kpis, MeterRegistry registry, Clock clock) {
        this.kpis = Objects.requireNonNull(kpis, "kpis");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lastSuccess = clock.instant();
        Gauge.builder(EXCLUDED, this, OnTimeRatioGauges::excludedPromiseUnknown)
                .description("정시율에서 빠진 결과 — 약속(또는 캠프)을 아직 모르는 완료·실패, 현재 버킷 포함 UTC "
                        + "정시 버킷 24개의 합. 정상에서는 프로젝션 랙만큼의 일시값이고 계속 0 이 아니면 "
                        + "fulfillment.planned 가 오지 않고 있다 (DESIGN.md §5.5 · §9.1)")
                .tag(TAG_REASON, PROMISE_UNKNOWN)
                .register(registry);
        Gauge.builder(REFRESH_AGE, this, OnTimeRatioGauges::refreshAgeSeconds)
                .description("마지막으로 성공한 KPI 갱신 뒤로 흐른 초 — 갱신이 죽으면 정시율은 NaN 이 되고 "
                        + "NaN 은 알림을 울리지 않으므로 이 값이 대신 커진다 (DESIGN.md §9.1 · §9.4)")
                .register(registry);
    }

    /**
     * 1분마다 다시 센다. 실패하면 정시율·빠진 수를 {@code NaN} 으로 두고 다음 실행을 기다린다 — 정시율은
     * 정확성이 아니라 관측이라 재시도할 이유가 없고, 틀린 값을 남기는 것보다 모름을 남기는 편이 낫다.
     */
    @Scheduled(fixedDelayString = "${dawnline.ops.kpi.on-time-refresh-ms:60000}",
            initialDelayString = "${dawnline.ops.kpi.on-time-initial-delay-ms:0}")
    public void refresh() {
        try {
            refreshNow();
        } catch (RuntimeException e) {
            latest = null;
            log.warn("KPI 갱신 실패 — 다음 성공까지 정시율·빠진 수는 NaN 이고 갱신 나이가 커집니다.", e);
        }
    }

    /**
     * 스케줄과 무관하게 지금 다시 센다(테스트·운영 수동 실행). 예외를 삼키지 않는다.
     */
    public void refreshNow() {
        Instant last = clock.instant().truncatedTo(ChronoUnit.HOURS);
        Instant first = last.minus(Duration.ofHours(BUCKETS - 1L));
        DeliveryWindow window = kpis.window(first, last);
        Map<UUID, CampDeliveries> camps = new HashMap<>();
        for (CampDeliveries camp : window.camps()) {
            camps.put(camp.campId(), camp);
            register(camp.campId());
        }
        latest = new Snapshot(Map.copyOf(camps), window.outcomeWithoutPromise());
        lastSuccess = clock.instant();
    }

    /**
     * 지금 게이지가 말하는 값.
     *
     * @param campId 캠프
     * @param basis  기준
     * @return 정시율, 모르면 {@code NaN}
     */
    public double ratio(UUID campId, Basis basis) {
        Snapshot snapshot = latest;
        CampDeliveries camp = snapshot == null ? null : snapshot.camps().get(campId);
        if (camp == null || camp.decided() == 0) {
            return Double.NaN;
        }
        long onTime = basis == Basis.PROMISED ? camp.onTimePromised() : camp.onTimeRevised();
        return (double) onTime / camp.decided();
    }

    /**
     * 정시율에서 빠진 결과의 수.
     *
     * @return 약속을 모르는 결과의 수, 모르면 {@code NaN}
     */
    public double excludedPromiseUnknown() {
        Snapshot snapshot = latest;
        return snapshot == null ? Double.NaN : snapshot.outcomeWithoutPromise();
    }

    /**
     * 마지막으로 성공한 갱신 뒤로 흐른 초 — 부를 때마다 시계를 본다.
     *
     * @return 초
     */
    public double refreshAgeSeconds() {
        return Duration.between(lastSuccess, clock.instant()).toMillis() / 1000.0;
    }

    private void register(UUID campId) {
        if (!registered.add(campId)) {
            return;
        }
        for (Basis basis : Basis.values()) {
            Gauge.builder(ON_TIME_RATIO, this, gauges -> gauges.ratio(campId, basis))
                    .description("정시 배송률 — 현재 버킷 포함 UTC 정시 버킷 24개(창은 23시간 남짓~24시간), "
                            + "정시 / (완료 + 실패). 원 약속(promised)이 SLO 기준이고 개정 약속(revised)은 "
                            + "참고값이다 (DESIGN.md §8.1 · §9.1)")
                    .tag(TAG_CAMP, campId.toString())
                    .tag(TAG_BASIS, basis.label())
                    .register(registry);
        }
    }

    private record Snapshot(Map<UUID, CampDeliveries> camps, long outcomeWithoutPromise) {
    }
}
