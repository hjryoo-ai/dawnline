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

    /** 주문 하나를 편입한다. */
    public void addOrder() {
        requireOpen("주문 편입");
        orderCount++;
    }

    /**
     * 주문 하나를 뺀다 (취소).
     *
     * <p><strong>{@code OPEN} 일 때만 부른다.</strong> 마감 이후의 취소는 카운트를 건드리지 않는다 —
     * {@code wave.closed} 가 이미 그 {@code orderCount} 로 나갔고, 지금 줄이면 "그때 몇 건이
     * 있었나" 에 답이 둘이 된다 (ADR-022). 그 분기는 호출부가 {@link #status()} 로 판단한다.
     */
    public void removeOrder() {
        requireOpen("주문 제거");
        if (orderCount == 0) {
            throw new IllegalStateException("편입된 주문이 없는 웨이브에서 뺄 수 없습니다: " + id);
        }
        orderCount--;
    }

    /** 마감을 시작한다 ({@code OPEN → CLOSING}). */
    public void beginClosing() {
        transitionTo(WaveStatus.CLOSING);
    }

    /**
     * 마감을 마친다 ({@code CLOSING → CLOSED}).
     *
     * @param at {@code wave.closed} 를 outbox 에 넣고 커밋하는 시각
     */
    public void close(Instant at) {
        Objects.requireNonNull(at, "at");
        transitionTo(WaveStatus.CLOSED);
        this.closedAt = at;
    }

    /** 하류가 계획을 마쳤다 ({@code CLOSED → PLANNED}, Phase 3). */
    public void markPlanned() {
        transitionTo(WaveStatus.PLANNED);
    }

    /** 하류의 계획이 실패했다 ({@code CLOSED → PLAN_FAILED}, Phase 3). */
    public void markPlanFailed() {
        transitionTo(WaveStatus.PLAN_FAILED);
    }

    private void transitionTo(WaveStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateTransitionException("Wave", status, next);
        }
        status = next;
    }

    private void requireOpen(String what) {
        if (!status.acceptsOrders()) {
            throw new IllegalStateTransitionException("Wave(" + what + ")", status, WaveStatus.OPEN);
        }
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

    /** 편입된 주문 수. */
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
