package com.dawnline.fulfillment.domain;

import com.dawnline.common.TimeWindow;
import com.dawnline.common.error.IllegalStateTransitionException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * fulfillment 가 한 주문에 대해 아는 것 전부 (ADR-022).
 *
 * <p>어느 웨이브·FC·권역인지, 왜 배차할 수 없는지, 약속이 개정됐는지, 취소됐는지를 한 행에
 * 담는다. 이 애그리거트가 없던 시절({@code wave_orders} 만 있던 때)에는 "주문 X 는 왜 웨이브에
 * 없나" 에 답할 수 없었다 — 배차 불가도, 취소도, 아직 안 온 것도 모두 "행이 없음" 이었기 때문이다.
 *
 * <h2>취소 선착에 별도 마커가 필요 없는 이유</h2>
 * {@link FulfillmentOrderStatus} 의 축 규칙이 그대로 처리한다. 취소가 먼저 오면
 * {@code CANCELLED}({@code placedEventId == null}) 행이 생기고, 뒤늦게 온 {@code order.placed} 는
 * {@link #ignoresPlaced()} 가 참이라 무시된다. 새 분기가 아니라 이미 있는 규칙의 결과다.
 */
public final class FulfillmentOrder {

    private final UUID orderId;

    private FulfillmentOrderStatus status;
    private @Nullable UUID waveId;
    private @Nullable UUID campId;
    private @Nullable UUID fcId;
    private @Nullable UUID zoneId;
    private @Nullable Instant cutoffAt;
    private @Nullable TimeWindow promisedWindow;
    private boolean promiseRevised;
    private @Nullable UnserviceableReason unserviceableReason;
    private @Nullable FcFallbackReason fcFallbackReason;
    private @Nullable UUID placedEventId;
    private @Nullable Instant cancelledAt;
    private long version;

    private FulfillmentOrder(UUID orderId, FulfillmentOrderStatus status) {
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.status = Objects.requireNonNull(status, "status");
    }

    /**
     * 계획됐다 — 웨이브에 편입됐다.
     *
     * @param orderId        주문 id
     * @param placedEventId  이 판정을 만든 {@code order.placed} 의 봉투 eventId
     * @param waveId         편입된 웨이브
     * @param campId         캠프
     * @param fcId           선택된 FC
     * @param zoneId         권역
     * @param cutoffAt       웨이브의 컷오프 (ADR-020 — order-service 가 계산한 값)
     * @param promisedWindow 지금 유효한 약속창
     * @param promiseRevised 그 창이 접수 시점의 약속과 다른가 (ADR-020)
     * @param fallbackReason 홈 FC 가 아닌 대체 FC 를 골랐다면 그 사유 (ADR-021)
     */
    public static FulfillmentOrder planned(UUID orderId, UUID placedEventId, UUID waveId, UUID campId,
            UUID fcId, UUID zoneId, Instant cutoffAt, TimeWindow promisedWindow, boolean promiseRevised,
            @Nullable FcFallbackReason fallbackReason) {

        FulfillmentOrder order = new FulfillmentOrder(orderId, FulfillmentOrderStatus.PLANNED);
        order.placedEventId = Objects.requireNonNull(placedEventId, "placedEventId");
        order.waveId = Objects.requireNonNull(waveId, "waveId");
        order.campId = Objects.requireNonNull(campId, "campId");
        order.fcId = Objects.requireNonNull(fcId, "fcId");
        order.zoneId = Objects.requireNonNull(zoneId, "zoneId");
        order.cutoffAt = Objects.requireNonNull(cutoffAt, "cutoffAt");
        order.promisedWindow = Objects.requireNonNull(promisedWindow, "promisedWindow");
        order.promiseRevised = promiseRevised;
        order.fcFallbackReason = fallbackReason;
        return order;
    }

    /**
     * 배차할 수 없다 (§5.2 6단계).
     *
     * @param orderId       주문 id
     * @param placedEventId 이 판정을 만든 {@code order.placed} 의 봉투 eventId
     * @param reason        사유
     * @param campId        캠프를 정한 뒤에 실패했다면 그 캠프. 권역을 못 찾았으면 {@code null}
     */
    public static FulfillmentOrder unserviceable(UUID orderId, UUID placedEventId,
            UnserviceableReason reason, @Nullable UUID campId) {

        FulfillmentOrder order = new FulfillmentOrder(orderId, FulfillmentOrderStatus.UNSERVICEABLE);
        order.placedEventId = Objects.requireNonNull(placedEventId, "placedEventId");
        order.unserviceableReason = Objects.requireNonNull(reason, "reason");
        order.campId = campId;
        return order;
    }

    /**
     * 취소가 {@code order.placed} 보다 <strong>먼저</strong> 왔다 (§4.5 순서 뒤바뀜).
     *
     * <p>{@code placedEventId} 가 비어 있는 것이 그 사실의 기록이다. 나중에 {@code order.placed}
     * 가 도착하면 {@link #ignoresPlaced()} 가 참이라 무시된다.
     *
     * @param orderId 주문 id
     * @param at      취소 시각
     */
    public static FulfillmentOrder cancelledBeforePlaced(UUID orderId, Instant at) {
        FulfillmentOrder order = new FulfillmentOrder(orderId, FulfillmentOrderStatus.CANCELLED);
        order.cancelledAt = Objects.requireNonNull(at, "at");
        return order;
    }

    /**
     * 저장된 상태에서 되살린다 (영속성 어댑터 전용).
     *
     * @param orderId 주문 id
     * @param status  상태
     * @param version 낙관적 락 버전
     */
    public static FulfillmentOrder rehydrate(UUID orderId, FulfillmentOrderStatus status, long version) {
        FulfillmentOrder order = new FulfillmentOrder(orderId, status);
        order.version = version;
        return order;
    }

    /**
     * 지금 도착한 {@code order.placed} 를 무시해야 하는가 (축 규칙).
     *
     * <p>이 행이 이미 판정을 지난 지점에 있다는 뜻이다. 대표적인 경우가 <strong>취소 선착</strong>
     * 이고, 그때 리스너는 {@code dawnline_event_rejected_total{reason="cancelled_before_placed"}}
     * 를 올리고 커밋한다 (ADR-022).
     */
    public boolean ignoresPlaced() {
        return status.hasProgressedPast(FulfillmentOrderStatus.PLANNED);
    }

    /**
     * 취소한다.
     *
     * <p>웨이브 카운트를 줄일지는 <strong>이 애그리거트가 정하지 않는다.</strong> 웨이브 상태에
     * 달린 판단이고(ADR-022 의 분기표), 웨이브는 다른 애그리거트다.
     *
     * @param at 취소 시각
     */
    public void cancel(Instant at) {
        Objects.requireNonNull(at, "at");
        if (!status.canTransitionTo(FulfillmentOrderStatus.CANCELLED)) {
            throw new IllegalStateTransitionException(
                    "FulfillmentOrder", status, FulfillmentOrderStatus.CANCELLED);
        }
        status = FulfillmentOrderStatus.CANCELLED;
        cancelledAt = at;
    }

    /**
     * 약속창을 개정한다 (ADR-020 결정 3).
     *
     * <p>grace 를 넘겨 도착해 다음 웨이브로 밀린 주문의 새 창이다. {@code promiseRevised} 가
     * 참이 되고, 그 값이 {@code fulfillment.planned} 를 타고 order-service 로 돌아가 고객의
     * 약속을 갱신한다 — 조용히 깨지 않기 위한 경로다.
     *
     * @param window 개정된 약속창
     * @param waveId 새로 편입된 웨이브
     */
    public void revisePromise(TimeWindow window, UUID waveId) {
        if (status != FulfillmentOrderStatus.PLANNED) {
            throw new IllegalStateTransitionException(
                    "FulfillmentOrder(약속 개정)", status, FulfillmentOrderStatus.PLANNED);
        }
        this.promisedWindow = Objects.requireNonNull(window, "window");
        this.waveId = Objects.requireNonNull(waveId, "waveId");
        this.promiseRevised = true;
    }

    /** 주문 id. */
    public UUID orderId() {
        return orderId;
    }

    /** 현재 상태. */
    public FulfillmentOrderStatus status() {
        return status;
    }

    /** 편입된 웨이브. {@code PLANNED} 일 때만 있다. */
    public Optional<UUID> waveId() {
        return Optional.ofNullable(waveId);
    }

    /** 캠프. */
    public Optional<UUID> campId() {
        return Optional.ofNullable(campId);
    }

    /** 선택된 FC. */
    public Optional<UUID> fcId() {
        return Optional.ofNullable(fcId);
    }

    /** 권역. */
    public Optional<UUID> zoneId() {
        return Optional.ofNullable(zoneId);
    }

    /** 웨이브의 컷오프. */
    public Optional<Instant> cutoffAt() {
        return Optional.ofNullable(cutoffAt);
    }

    /** 지금 유효한 약속창. */
    public Optional<TimeWindow> promisedWindow() {
        return Optional.ofNullable(promisedWindow);
    }

    /** 약속창이 접수 시점의 것과 다른가. */
    public boolean promiseRevised() {
        return promiseRevised;
    }

    /** 배차 불가 사유. */
    public Optional<UnserviceableReason> unserviceableReason() {
        return Optional.ofNullable(unserviceableReason);
    }

    /** 대체 FC 를 고른 사유. */
    public Optional<FcFallbackReason> fcFallbackReason() {
        return Optional.ofNullable(fcFallbackReason);
    }

    /** 이 행을 만든 {@code order.placed} 의 eventId. 비어 있으면 취소 선착이다. */
    public Optional<UUID> placedEventId() {
        return Optional.ofNullable(placedEventId);
    }

    /** 취소 시각. */
    public Optional<Instant> cancelledAt() {
        return Optional.ofNullable(cancelledAt);
    }

    /** 낙관적 락 버전. */
    public long version() {
        return version;
    }
}
