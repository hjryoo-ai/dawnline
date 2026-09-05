package com.dawnline.fulfillment.adapter.out.persistence;

import com.dawnline.common.TimeWindow;
import com.dawnline.fulfillment.domain.FcFallbackReason;
import com.dawnline.fulfillment.domain.FulfillmentOrder;
import com.dawnline.fulfillment.domain.FulfillmentOrderStatus;
import com.dawnline.fulfillment.domain.UnserviceableReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code fulfillment_orders} 행 (ADR-022, DESIGN.md §5.2).
 *
 * <p>주문 하나당 행 하나다. 이 표가 없던 시절({@code wave_orders} 만 있던 때)에는 배차 불가도,
 * 취소도, 아직 안 온 것도 모두 "행이 없음" 이라 구별되지 않았다.
 *
 * <p>약속창은 {@code promised_start}/{@code promised_end} 두 컬럼이고 도메인은
 * {@link TimeWindow} 하나다. 둘 다 있거나 둘 다 없어야 하며, 그 불변식은 이 클래스의 경계에서
 * 지킨다 — DDL 에 CHECK 를 걸지 않는 것은 상태 규칙을 두 곳에 두지 않기 위해서다(ADR-022).
 */
@Entity
@Table(name = "fulfillment_orders")
public class FulfillmentOrderEntity {

    @Id
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private FulfillmentOrderStatus status;

    @Column(name = "wave_id")
    private @Nullable UUID waveId;

    @Column(name = "camp_id")
    private @Nullable UUID campId;

    @Column(name = "fc_id")
    private @Nullable UUID fcId;

    @Column(name = "zone_id")
    private @Nullable UUID zoneId;

    @Column(name = "cutoff_at")
    private @Nullable Instant cutoffAt;

    @Column(name = "promised_start")
    private @Nullable Instant promisedStart;

    @Column(name = "promised_end")
    private @Nullable Instant promisedEnd;

    @Column(name = "promise_revised", nullable = false)
    private boolean promiseRevised;

    @Enumerated(EnumType.STRING)
    @Column(name = "unserviceable_reason", length = 24)
    private @Nullable UnserviceableReason unserviceableReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "fc_fallback_reason", length = 16)
    private @Nullable FcFallbackReason fcFallbackReason;

    /** {@code NULL} 이면 {@code order.placed} 가 아직 오지 않았다 = 취소 선착 (ADR-022 결정 3). */
    @Column(name = "placed_event_id")
    private @Nullable UUID placedEventId;

    @Column(name = "cancelled_at")
    private @Nullable Instant cancelledAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 보존 정리의 기준 (ADR-023 결정 1). {@code ix_fulfillment_orders_cleanup} 이 이 컬럼이다. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 전용. */
    protected FulfillmentOrderEntity() {
    }

    /**
     * 도메인 애그리거트를 행으로 옮긴다.
     *
     * @param order 도메인 주문
     */
    public static FulfillmentOrderEntity from(FulfillmentOrder order) {
        FulfillmentOrderEntity entity = new FulfillmentOrderEntity();
        entity.orderId = order.orderId();
        entity.createdAt = order.createdAt();
        entity.applyStateOf(order);
        return entity;
    }

    /**
     * 이미 저장된 행에 도메인의 변경을 반영한다.
     *
     * <p>{@code created_at} 과 {@code order_id} 는 건드리지 않는다. 나머지는 전부 옮긴다 —
     * 애그리거트가 {@link FulfillmentOrder#rehydrate} 로 <strong>모든 필드를 복원</strong>하므로
     * 부분 반영으로 나눌 이유가 없고, 나누면 새 컬럼이 생길 때 조용히 빠진다.
     *
     * @param order 변경된 도메인 주문
     */
    public void applyStateOf(FulfillmentOrder order) {
        if (orderId != null && !orderId.equals(order.orderId())) {
            throw new IllegalArgumentException(
                    "다른 주문의 상태를 반영할 수 없습니다: " + orderId + " ← " + order.orderId());
        }
        this.status = order.status();
        this.waveId = order.waveId().orElse(null);
        this.campId = order.campId().orElse(null);
        this.fcId = order.fcId().orElse(null);
        this.zoneId = order.zoneId().orElse(null);
        this.cutoffAt = order.cutoffAt().orElse(null);
        this.promisedStart = order.promisedWindow().map(TimeWindow::start).orElse(null);
        this.promisedEnd = order.promisedWindow().map(TimeWindow::end).orElse(null);
        this.promiseRevised = order.promiseRevised();
        this.unserviceableReason = order.unserviceableReason().orElse(null);
        this.fcFallbackReason = order.fcFallbackReason().orElse(null);
        this.placedEventId = order.placedEventId().orElse(null);
        this.cancelledAt = order.cancelledAt().orElse(null);
        this.updatedAt = order.updatedAt();
    }

    /** 행을 도메인 애그리거트로 되살린다. */
    public FulfillmentOrder toDomain() {
        TimeWindow window = promisedStart == null || promisedEnd == null
                ? null
                : new TimeWindow(promisedStart, promisedEnd);
        return FulfillmentOrder.rehydrate(orderId, status, waveId, campId, fcId, zoneId, cutoffAt,
                window, promiseRevised, unserviceableReason, fcFallbackReason, placedEventId,
                cancelledAt, createdAt, updatedAt, version);
    }

    /** 주문 id. */
    public UUID orderId() {
        return orderId;
    }

    /** 현재 상태. */
    public FulfillmentOrderStatus status() {
        return status;
    }

    /** 낙관적 락 버전. */
    public long version() {
        return version;
    }
}
