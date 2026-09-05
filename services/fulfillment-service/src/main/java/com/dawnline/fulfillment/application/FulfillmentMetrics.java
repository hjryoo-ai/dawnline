package com.dawnline.fulfillment.application;

import com.dawnline.fulfillment.domain.FcFallbackReason;
import com.dawnline.fulfillment.domain.ServiceTier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** 개정 카운터 이름 (§9.1). */
    public static final String PROMISE_REVISED = "dawnline.promise.revised";

    /** 대체 FC 카운터 이름 (§9.1). */
    public static final String FC_FALLBACK = "dawnline.fc.fallback";

    /** 웨이브 편입량 게이지 이름 (§9.1). */
    public static final String WAVE_ORDERS = "dawnline.wave.orders";

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
     * <p><strong>이 값이 0 이 아니라는 것은 grace 로 흡수하지 못한 지연이 있었다는 뜻이고,
     * 늘어나면 grace 를 늘릴 것이 아니라 지연의 원인을 봐야 한다.</strong>
     *
     * @param campCode 캠프 코드
     * @param tier     티어
     */
    public void promiseRevised(String campCode, ServiceTier tier) {
        Counter.builder(PROMISE_REVISED)
                .description("하류가 상류의 약속을 개정한 횟수 (ADR-020 결정 3)")
                .tag("camp", campCode)
                .tag("tier", tier.name())
                .register(registry)
                .increment();
    }

    /**
     * 홈 FC 가 필터에서 떨어져 대체 FC 를 골랐다 (ADR-021 결정 3).
     *
     * @param campCode 캠프 코드
     * @param reason   홈 FC 가 떨어진 필터
     */
    public void fcFallback(String campCode, FcFallbackReason reason) {
        Counter.builder(FC_FALLBACK)
                .description("캠프의 홈 FC 가 필터에서 떨어져 대체 FC 를 고른 횟수 (ADR-021)")
                .tag("camp", campCode)
                .tag("reason", reason.name().toLowerCase())
                .register(registry)
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
        // AtomicInteger 를 들고 있어야 한다. registry.gauge 는 (이름, 라벨)이 같으면 기존 미터를
        // 돌려주고 새 값을 반영하지 않으며, 참조도 약한 참조라 박싱된 Integer 를 넘기면 GC 뒤
        // 게이지가 NaN 이 된다. GeoMetrics 의 적재 게이지와 같은 이유·같은 방식이다.
        gauges.computeIfAbsent(campCode + "/" + tier.name(), key -> registry.gauge(WAVE_ORDERS,
                        io.micrometer.core.instrument.Tags.of("camp", campCode, "tier", tier.name()),
                        new AtomicInteger()))
                .set(orderCount);
    }
}
