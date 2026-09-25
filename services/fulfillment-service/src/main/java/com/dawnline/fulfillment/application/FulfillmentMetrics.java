package com.dawnline.fulfillment.application;

import com.dawnline.fulfillment.domain.FcFallbackReason;
import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.WaveCloseCause;
import com.dawnline.observability.DawnlineMeters;
import com.dawnline.observability.DawnlineMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * fulfillment 고유 메트릭 (DESIGN.md §9.1).
 *
 * <p>셋 다 <strong>세는 값이지 알림이 아니다</strong> — 다만 뜻이 다르다.
 *
 * <ul>
 *   <li>{@code dawnline_wave_orders} — 진행 중 웨이브의 편입량. 게이지이고, {@code waves.order_count}
 *       가 마감 전에는 0 이므로(ADR-025) 이 값이 유일한 관측 경로다</li>
 *   <li>{@code dawnline_promise_revised_total} — 개정이 <em>실제로 일어났는지</em>를 보는 유일한
 *       값이다. 이것이 없으면 §8.1 의 정시율 두 기준을 나중에 맞출 수 없다 (ADR-020 결정 3)</li>
 *   <li>{@code dawnline_fc_fallback_total} — 대체 FC 선택이 조용히 일어나지 않게 한다. 계속 오르는
 *       캠프는 홈 FC 배정이 잘못됐거나 그 FC 의 역량이 부족한 것이고, 그것이 §5.2 FC 선택 규칙이
 *       드러내려던 사실이다 (ADR-021)</li>
 * </ul>
 *
 * <h2>왜 application 에 있는가</h2>
 * 이 값들을 올리는 곳이 유스케이스({@code PlanOrderService}·{@code CloseDueWavesService})이기
 * 때문이다. adapter 에 두면 application 이 adapter 를 역참조하게 되고, 그것은 헥사고날 규칙 2 가
 * 막는 방향이다(ArchUnit 이 잡는다). Micrometer 는 어댑터가 아니라 <em>계측 파사드</em>이므로
 * {@code libs/messaging} 의 {@code IdempotentConsumer} 와 같은 자리에 둔다.
 *
 * <p>캠프를 <strong>코드</strong>로 라벨링한다. UUID 를 라벨에 넣으면 대시보드에서 읽을 수 없고,
 * 캠프는 10개 안팎이라 카디널리티 문제도 없다.
 */
public class FulfillmentMetrics {

    /**
     * 원래 컷오프 웨이브가 닫혀 있었지만 원인을 모를 때의 라벨 값.
     *
     * <p>커밋된 행에서는 나올 수 없다 — 닫힌 웨이브는 {@code close_cause} 를 들고 있고(V3 CHECK),
     * {@code CLOSING} 은 커밋된 적이 없다. 그래도 개정은 세야 하므로 예외 대신 이 값으로 센다: 이 값이
     * 보이면 그 불변식이 깨졌다는 뜻이다.
     */
    public static final String CAUSE_UNKNOWN = "unknown";

    private final MeterRegistry registry;
    private final Map<String, AtomicInteger> gauges = new ConcurrentHashMap<>();

    /**
     * @param registry 미터 레지스트리
     */
    public FulfillmentMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * 하류가 상류의 약속을 개정했다 (ADR-020 결정 3).
     *
     * <p><strong>{@code cause="scheduled"} 가 0 이 아니라는 것은 grace 로 흡수하지 못한 지연이 있었다는
     * 뜻이고, 늘어나면 grace 를 늘릴 것이 아니라 지연의 원인을 봐야 한다.</strong> {@code cause="manual"} 은
     * 운영자가 앞당긴 컷오프의 대가다 — 결정이 낳은 값이고, 이유는 감사 행에 있다(ADR-054 결정 4).
     * 둘을 한 값으로 세면 사람이 누른 결과가 「grace 가 모자라다」로 읽힌다.
     *
     * @param campCode 캠프 코드
     * @param tier     티어
     * @param cause    주문의 원래 컷오프 웨이브를 누가 닫았는가. 모르면 {@code null}
     */
    public void promiseRevised(String campCode, ServiceTier tier, @Nullable WaveCloseCause cause) {
        DawnlineMeters.counter(registry, DawnlineMetrics.PROMISE_REVISED,
                "camp", campCode,
                "tier", tier.name(),
                "cause", cause == null ? CAUSE_UNKNOWN : cause.name().toLowerCase(Locale.ROOT))
                .increment();
    }

    /**
     * 홈 FC 가 필터에서 떨어져 대체 FC 를 골랐다 (ADR-021 결정 3).
     *
     * @param campCode 캠프 코드
     * @param reason   홈 FC 가 떨어진 필터
     */
    public void fcFallback(String campCode, FcFallbackReason reason) {
        DawnlineMeters.counter(registry, DawnlineMetrics.FC_FALLBACK,
                "camp", campCode,
                "reason", reason.name().toLowerCase())
                .increment();
    }

    /**
     * 웨이브에 편입된 주문 수를 기록한다.
     *
     * <p>게이지지만 콜백이 아니라 <em>마감 시점의 값</em>을 남긴다. 진행 중 웨이브를 매번 세면
     * 스크레이프마다 집계 쿼리가 돌고, 그것은 §8.2 피크에 관측이 부하가 되는 형태다 —
     * 마감할 때 이미 세는 값이 있으므로 그것을 그대로 쓴다 (ADR-025).
     *
     * @param campCode   캠프 코드
     * @param tier       티어
     * @param orderCount 마감 시점의 편입 주문 수
     */
    public void waveClosed(String campCode, ServiceTier tier, int orderCount) {
        // (캠프, 티어)마다 상태 하나를 들고 값을 바꾼다 — 같은 이름 · 태그로 다시 등록하면 레지스트리는 기존 미터를
        // 돌려주고 새 상태를 쓰지 않는다. 상태는 헬퍼가 강한 참조로 잡는다(ADR-060): 약한 참조였을 때 박싱된 값을 넘기면
        // GC 뒤 게이지가 NaN 이 됐다.
        gauges.computeIfAbsent(campCode + "/" + tier.name(), key -> {
                    AtomicInteger slot = new AtomicInteger();
                    DawnlineMeters.gauge(registry, DawnlineMetrics.WAVE_ORDERS, slot, AtomicInteger::doubleValue,
                            "camp", campCode, "tier", tier.name());
                    return slot;
                })
                .set(orderCount);
    }
}
