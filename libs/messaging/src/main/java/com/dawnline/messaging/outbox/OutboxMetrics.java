package com.dawnline.messaging.outbox;

import com.dawnline.messaging.MessagingMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * outbox 게이지 세 개 (DESIGN.md §9.1).
 *
 * <ul>
 *   <li>{@code dawnline_outbox_lag_seconds{service}} — 가장 오래된 미발행 행의 나이</li>
 *   <li>{@code dawnline_outbox_unpublished{service}} — 미발행 행 수 (격리 행 제외)</li>
 *   <li>{@code dawnline_outbox_failed{service}} — 격리된 행 수 (§4.6, ADR-015)</li>
 * </ul>
 *
 * <p>미발행과 격리는 <strong>서로 배타적</strong>이다. 같은 행이 두 게이지에 동시에 잡히면
 * "미발행 0건인데 지연은 계속 올라간다" 같은 모순된 대시보드가 나온다.
 *
 * <p>게이지 값은 {@link OutboxRelay} 가 주기적으로 {@link #refresh(long, double, long)} 로 갱신한다.
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
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong leader = new AtomicLong();

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

        Gauge.builder(MessagingMetrics.OUTBOX_FAILED, failed, AtomicLong::doubleValue)
                .description("결정적 실패로 격리된 outbox 행 수. 0 이 아니면 사람이 봐야 한다(RB-05).")
                .tags(tags)
                .register(registry);

        Gauge.builder(MessagingMetrics.OUTBOX_LEADER, leader, AtomicLong::doubleValue)
                .description("릴레이 리더십. 1 리더 · 0 팔로워(정상) · -1 판정 불가(Redis 장애).")
                .tags(tags)
                .register(registry);
    }

    /**
     * 게이지 값을 갱신한다.
     *
     * @param unpublishedCount 미발행 행 수 (격리 행 제외)
     * @param lagSeconds       가장 오래된 미발행 행의 나이(초). 미발행이 없으면 0.
     * @param failedCount      격리된 행 수
     */
    public void refresh(long unpublishedCount, double lagSeconds, long failedCount) {
        unpublished.set(unpublishedCount);
        lagMillis.set(Math.round(lagSeconds * 1000.0));
        failed.set(failedCount);
    }

    /** 현재 게이지가 들고 있는 미발행 행 수. 테스트용. */
    public long unpublishedCount() {
        return unpublished.get();
    }

    /** 현재 게이지가 들고 있는 지연(초). 테스트용. */
    public double lagSeconds() {
        return lagMillis.get() / 1000.0;
    }

    /**
     * 리더십 게이지를 갱신한다 (ADR-027).
     *
     * <p>{@link #refresh} 와 나눠 둔 이유: 저쪽은 5초마다 DB 를 읽어 갱신하고 이쪽은 100ms 마다
     * 폴링이 판정한 값을 그대로 쓴다. 주기가 다르고 출처가 다르다.
     *
     * @param state 릴레이가 방금 판정한 리더십
     */
    public void leadership(RelayLeadership.State state) {
        leader.set(switch (state) {
            case LEADER -> 1L;
            case FOLLOWER -> 0L;
            case UNKNOWN -> -1L;
        });
    }

    /** 현재 게이지가 들고 있는 격리 행 수. 테스트용. */
    public long failedCount() {
        return failed.get();
    }

    /** 현재 게이지가 들고 있는 리더십 값. 테스트용. */
    public long leaderValue() {
        return leader.get();
    }
}
