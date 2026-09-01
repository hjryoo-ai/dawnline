package com.dawnline.messaging.outbox;

import com.dawnline.messaging.MessagingMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * outbox 게이지 두 개 (DESIGN.md §9.1).
 *
 * <ul>
 *   <li>{@code dawnline_outbox_lag_seconds{service}} — 가장 오래된 미발행 행의 나이</li>
 *   <li>{@code dawnline_outbox_unpublished{service}} — 미발행 행 수</li>
 * </ul>
 *
 * <p>게이지 값은 {@link OutboxRelay} 가 주기적으로 {@link #refresh(long, double)} 로 갱신한다.
 * Micrometer 의 콜백형 게이지(스크레이프 시점에 DB 조회)를 쓰지 않은 이유: Prometheus 스크레이프가
 * DB 쿼리를 유발하면 스크레이프 간격이 곧 DB 부하가 되고, 스크레이프 타임아웃이 DB 지연에 연동된다.
 * 값을 미리 계산해 두면 스크레이프는 메모리 읽기다.
 *
 * <p>지연은 밀리초 정수로 보관하고 초로 환산해 노출한다. {@code double} 필드를 원자적으로 갱신하려면
 * 별도 타입이 필요한데, 밀리초 정밀도면 이 지표에 충분하다(§8.1 목표는 p95 2초).
 */
public class OutboxMetrics {

    private final AtomicLong unpublished = new AtomicLong();
    private final AtomicLong lagMillis = new AtomicLong();

    /**
     * @param registry Micrometer 레지스트리
     * @param service  {@code service} 태그 값 (§9.1)
     */
    public OutboxMetrics(MeterRegistry registry, String service) {
        Objects.requireNonNull(registry, "registry");
        Tags tags = Tags.of(MessagingMetrics.TAG_SERVICE, Objects.requireNonNull(service, "service"));

        Gauge.builder(MessagingMetrics.OUTBOX_UNPUBLISHED, unpublished, AtomicLong::doubleValue)
                .description("아직 Kafka 로 발행되지 않은 outbox 행 수")
                .tags(tags)
                .register(registry);

        Gauge.builder(MessagingMetrics.OUTBOX_LAG_SECONDS, lagMillis, millis -> millis.doubleValue() / 1000.0)
                .description("가장 오래된 미발행 outbox 행이 만들어진 뒤 흐른 시간(초)")
                .tags(tags)
                .register(registry);
    }

    /**
     * 게이지 값을 갱신한다.
     *
     * @param unpublishedCount 미발행 행 수
     * @param lagSeconds       가장 오래된 미발행 행의 나이(초). 미발행이 없으면 0.
     */
    public void refresh(long unpublishedCount, double lagSeconds) {
        unpublished.set(unpublishedCount);
        lagMillis.set(Math.round(lagSeconds * 1000.0));
    }

    /** 현재 게이지가 들고 있는 미발행 행 수. 테스트용. */
    public long unpublishedCount() {
        return unpublished.get();
    }

    /** 현재 게이지가 들고 있는 지연(초). 테스트용. */
    public double lagSeconds() {
        return lagMillis.get() / 1000.0;
    }
}
