package com.dawnline.ops.application;

import com.dawnline.ops.application.port.out.DeliveryKpis;
import com.dawnline.ops.application.port.out.DeliveryKpis.CampDeliveries;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * {@code dawnline_delivery_on_time_ratio{camp, basis}} — 정시율 두 값을 따로 낸다 (DESIGN.md §9.1 · §8.1).
 *
 * <h2>창은 배송 축의 시간 버킷 24개다</h2>
 * 지금 시각이 든 버킷과 그 앞의 23개 — {@code kpi_delivery_hourly} 의 행 24개의 합이다. 게이지가 뷰를
 * 읽으므로 대시보드의 24행과 게이지가 다른 수를 말할 수 없다. 버킷은 UTC 정시다(뷰와 같다).
 *
 * <h2>분모는 결과가 난 주문이다 — 실패를 포함한다</h2>
 * {@code 정시 / (완료 + 실패)}. 실패는 분모에 있고 분자에 없다 — 실패를 빼면 정시율이 오른다.
 * 취소·배차 불가는 뷰가 이미 뺐다.
 *
 * <h2>모름은 값이 아니다</h2>
 * 창 안에 결과가 없는 캠프, 그리고 갱신이 실패한 동안의 모든 캠프는 {@code NaN} 이다 — 0 도 아니고
 * 마지막 값도 아니다. 0 은 「전부 늦었다」는 주장이고, 멈춘 값은 건강해 보인다
 * ({@code dawnline_shipment_partitions_ahead} 와 같은 이유).
 *
 * <h2>미터는 캠프를 처음 볼 때 등록한다</h2>
 * 캠프 목록은 ops 의 사실이 아니다(fulfillment 의 표다). 창에 처음 나타난 캠프의 두 계열을 그때
 * 등록하고, 사라져도 지우지 않는다 — 그 뒤로는 {@code NaN} 을 말한다.
 */
public class OnTimeRatioGauges {

    /** §9.1 의 이름 — Prometheus 에서 {@code dawnline_delivery_on_time_ratio}. */
    public static final String ON_TIME_RATIO = "dawnline.delivery.on.time.ratio";

    static final String TAG_CAMP = "camp";
    static final String TAG_BASIS = "basis";

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
    private volatile Map<UUID, CampDeliveries> latest = Map.of();

    /**
     * @param kpis     배송 축 KPI
     * @param registry 미터 레지스트리
     * @param clock    창의 기준 시각 (불변규칙 12)
     */
    public OnTimeRatioGauges(DeliveryKpis kpis, MeterRegistry registry, Clock clock) {
        this.kpis = Objects.requireNonNull(kpis, "kpis");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 1분마다 다시 센다. 실패하면 모든 계열을 {@code NaN} 으로 두고 다음 실행을 기다린다 — 정시율은
     * 정확성이 아니라 관측이라 재시도할 이유가 없고, 틀린 값을 남기는 것보다 모름을 남기는 편이 낫다.
     */
    @Scheduled(fixedDelayString = "${dawnline.ops.kpi.on-time-refresh-ms:60000}",
            initialDelayString = "${dawnline.ops.kpi.on-time-initial-delay-ms:0}")
    public void refresh() {
        try {
            refreshNow();
        } catch (RuntimeException e) {
            latest = Map.of();
            log.warn("정시율 갱신 실패 — 다음 실행까지 게이지는 NaN 입니다.", e);
        }
    }

    /**
     * 스케줄과 무관하게 지금 다시 센다(테스트·운영 수동 실행). 예외를 삼키지 않는다.
     */
    public void refreshNow() {
        Instant last = clock.instant().truncatedTo(ChronoUnit.HOURS);
        Instant first = last.minus(Duration.ofHours(BUCKETS - 1L));
        Map<UUID, CampDeliveries> next = new HashMap<>();
        for (CampDeliveries camp : kpis.sumByCamp(first, last)) {
            next.put(camp.campId(), camp);
            register(camp.campId());
        }
        latest = Map.copyOf(next);
    }

    /**
     * 지금 게이지가 말하는 값.
     *
     * @param campId 캠프
     * @param basis  기준
     * @return 정시율, 모르면 {@code NaN}
     */
    public double ratio(UUID campId, Basis basis) {
        CampDeliveries camp = latest.get(campId);
        if (camp == null || camp.decided() == 0) {
            return Double.NaN;
        }
        long onTime = basis == Basis.PROMISED ? camp.onTimePromised() : camp.onTimeRevised();
        return (double) onTime / camp.decided();
    }

    private void register(UUID campId) {
        if (!registered.add(campId)) {
            return;
        }
        for (Basis basis : Basis.values()) {
            Gauge.builder(ON_TIME_RATIO, this, gauges -> gauges.ratio(campId, basis))
                    .description("정시 배송률 — 배송 축 버킷 24개, 정시 / (완료 + 실패). 원 약속(promised)이 "
                            + "SLO 기준이고 개정 약속(revised)은 참고값이다 (DESIGN.md §8.1 · §9.1)")
                    .tag(TAG_CAMP, campId.toString())
                    .tag(TAG_BASIS, basis.label())
                    .register(registry);
        }
    }
}
