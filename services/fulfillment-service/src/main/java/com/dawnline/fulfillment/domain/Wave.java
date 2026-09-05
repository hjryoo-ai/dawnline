package com.dawnline.fulfillment.domain;

import com.dawnline.common.error.IllegalStateTransitionException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 웨이브 애그리거트 (DESIGN.md §5.2).
 *
 * <p>웨이브는 {@code (campId, serviceTier, cutoffAt)} 당 하나다. {@code cutoffAt} 은
 * order-service 가 계산해 {@code order.placed} 에 실어 보낸 값을 <strong>그대로</strong> 쓴다 —
 * 여기서 다시 계산하지 않는다 (ADR-020).
 *
 * <p>상태 전이는 {@link WaveStatus} 의 표가 정하고, 이 클래스는 그 표를 지키며 부수 효과
 * ({@code closedAt}, {@code orderCount})를 함께 다룬다. 세터로 상태를 바꾸지 않는다(불변규칙 6).
 *
 * <h2>편입은 이 애그리거트를 바꾸지 않는다 (ADR-025)</h2>
 * 주문이 웨이브에 들어가는 것은 {@code fulfillment_orders} 에 행이 생기는 일이고, 웨이브 행은
 * <strong>읽기만</strong> 한다(상태가 {@code OPEN} 인지). 그래서 편입 경로는 {@code FOR SHARE} 로
 * 충분하고, 같은 웨이브로 몰리는 주문들이 서로를 막지 않는다 — 편입마다 {@code order_count} 를
 * 올리던 이전 설계는 배타 락을 요구했고, §8.2 피크에서 <em>웨이브 행 하나가 처리량 상한</em>이
 * 되었다.
 *
 * <p>{@code orderCount} 는 마감 시 한 번 세어 {@link #close(Instant, int)} 로 들어온다. 그래서
 * 취소가 카운트를 건드리는 분기가 없고, 카운터 드리프트도 구조적으로 불가능하다.
 */
public final class Wave {

    private final UUID id;
    private final UUID campId;
    private final ServiceTier serviceTier;
    private final Instant cutoffAt;

    private WaveStatus status;
    private int orderCount;
    private @Nullable Instant closedAt;
    private long version;

    private Wave(UUID id, UUID campId, ServiceTier serviceTier, Instant cutoffAt,
            WaveStatus status, int orderCount, @Nullable Instant closedAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.campId = Objects.requireNonNull(campId, "campId");
        this.serviceTier = Objects.requireNonNull(serviceTier, "serviceTier");
        this.cutoffAt = Objects.requireNonNull(cutoffAt, "cutoffAt");
        this.status = Objects.requireNonNull(status, "status");
        this.closedAt = closedAt;
        this.version = version;
        if (orderCount < 0) {
            throw new IllegalArgumentException("orderCount 는 0 이상이어야 합니다: " + orderCount);
        }
        this.orderCount = orderCount;
    }

    /**
     * 새 웨이브를 연다.
     *
     * @param id          웨이브 id (UUIDv7, 불변규칙 10)
     * @param campId      캠프
     * @param serviceTier 티어
     * @param cutoffAt    {@code order.placed} 가 싣고 온 컷오프
     */
    public static Wave open(UUID id, UUID campId, ServiceTier serviceTier, Instant cutoffAt) {
        return new Wave(id, campId, serviceTier, cutoffAt, WaveStatus.OPEN, 0, null, 0);
    }

    /**
     * 저장된 상태에서 되살린다 (영속성 어댑터 전용).
     *
     * @param id          웨이브 id
     * @param campId      캠프
     * @param serviceTier 티어
     * @param cutoffAt    컷오프
     * @param status      상태
     * @param orderCount  편입 주문 수
     * @param closedAt    마감 시각
     * @param version     낙관적 락 버전
     */
    public static Wave rehydrate(UUID id, UUID campId, ServiceTier serviceTier, Instant cutoffAt,
            WaveStatus status, int orderCount, @Nullable Instant closedAt, long version) {
        return new Wave(id, campId, serviceTier, cutoffAt, status, orderCount, closedAt, version);
    }

    /**
     * 마감할 때가 되었는가 — {@code cutoffAt + grace <= now} (ADR-020 결정 2).
     *
     * <p>grace 를 두는 이유는 outbox·컨슈머 지연을 흡수하기 위해서다. 그 시간 안에 도착한 주문은
     * 약속받은 그 웨이브에 그대로 들어간다.
     *
     * @param now   현재 시각 (주입된 {@code Clock} 에서 온다, 불변규칙 12)
     * @param grace 흡수 여유
     */
    public boolean isDueForClosing(Instant now, Duration grace) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(grace, "grace");
        return status == WaveStatus.OPEN && !cutoffAt.plus(grace).isAfter(now);
    }

    /**
     * 새 주문을 받을 수 있는가.
     *
     * <p>편입 경로가 {@code FOR SHARE} 로 행을 잡은 뒤 이것을 확인한다(ADR-025). 거짓이면 그
     * 주문은 다음 웨이브로 간다 — 예외가 아니라 정상 분기다.
     */
    public boolean acceptsOrders() {
        return status.acceptsOrders();
    }

    /** 마감을 시작한다 ({@code OPEN → CLOSING}). */
    public void beginClosing() {
        transitionTo(WaveStatus.CLOSING);
    }

    /**
     * 마감을 마친다 ({@code CLOSING → CLOSED}).
     *
     * <p>{@code orderCount} 를 <strong>여기서</strong> 받는다. 호출부가 마감 직전에
     * {@code fulfillment_orders} 를 세어 넘긴 값이고(ADR-025), 그 시점에는 배타 락을 들고 있어
     * 새 편입이 없다. 이 값이 그대로 {@code wave.closed} 로 나간다(§4.3).
     *
     * @param at         {@code wave.closed} 를 outbox 에 넣고 커밋하는 시각
     * @param orderCount 마감 시점에 이 웨이브에 편입되어 있던 주문 수
     */
    public void close(Instant at, int orderCount) {
        Objects.requireNonNull(at, "at");
        if (orderCount < 0) {
            throw new IllegalArgumentException("orderCount 는 0 이상이어야 합니다: " + orderCount);
        }
        transitionTo(WaveStatus.CLOSED);
        this.closedAt = at;
        this.orderCount = orderCount;
    }

    /**
     * 하류가 계획을 마쳤다 ({@code plan.completed} 수신, Phase 3).
     *
     * <p>{@code CLOSED} 에서도, 운영자 재실행이 성공한 {@code PLAN_FAILED} 에서도 온다
     * (ADR-024 결정 3). 이미 {@code PLANNED} 인데 또 오면 그것은 철 지난 이벤트이고, 여기까지
     * 오기 전에 리스너가 {@link WaveStatus#hasProgressedPast} 로 걸러 무시한다.
     */
    public void markPlanned() {
        transitionTo(WaveStatus.PLANNED);
    }

    /**
     * 하류의 계획이 실패했다 ({@code plan.failed} 수신, {@code CLOSED → PLAN_FAILED}, Phase 3).
     *
     * <p>이미 {@code PLANNED} 인 웨이브에 도착한 {@code plan.failed} 는 여기로 오지 않는다 —
     * 재실행이 만드는 순서 뒤바뀜이라 리스너가 축 규칙으로 흡수한다 (ADR-024 결정 4).
     */
    public void markPlanFailed() {
        transitionTo(WaveStatus.PLAN_FAILED);
    }

    private void transitionTo(WaveStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateTransitionException("Wave", status, next);
        }
        status = next;
    }

    /** 웨이브 id. */
    public UUID id() {
        return id;
    }

    /** 캠프 id. */
    public UUID campId() {
        return campId;
    }

    /** 서비스 티어. */
    public ServiceTier serviceTier() {
        return serviceTier;
    }

    /** 컷오프 시각. */
    public Instant cutoffAt() {
        return cutoffAt;
    }

    /** 현재 상태. */
    public WaveStatus status() {
        return status;
    }

    /**
     * 편입된 주문 수. <strong>마감 전에는 0 이다</strong> (ADR-025).
     *
     * <p>진행 중 웨이브의 편입량은 이 값이 아니라 {@code dawnline_wave_orders} 게이지가 본다 —
     * 그쪽은 {@code fulfillment_orders} 를 직접 센다(§9.1).
     */
    public int orderCount() {
        return orderCount;
    }

    /** 마감 시각. 마감 전이면 비어 있다. */
    public @Nullable Instant closedAt() {
        return closedAt;
    }

    /** 낙관적 락 버전. */
    public long version() {
        return version;
    }
}
