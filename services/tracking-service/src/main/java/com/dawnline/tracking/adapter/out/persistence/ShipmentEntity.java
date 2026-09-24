package com.dawnline.tracking.adapter.out.persistence;

import com.dawnline.tracking.domain.Shipment;
import com.dawnline.tracking.domain.ShipmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code shipments} 행 (DESIGN.md §5.4).
 *
 * <p>도메인과 엔티티를 나눈다(ADR-007) — {@link Shipment} 는 JPA 를 모른다(불변규칙 5).
 *
 * <p>{@code stop_seq} 는 {@code SMALLINT} 다. 도메인은 {@code int} 로 다루고(계약의 상한이
 * 32767 이라 그 안에서 안전하다) 여기서만 {@code short} 로 좁힌다.
 *
 * <p>{@code updated_at} 은 도메인에 없다 — 사실이 아니라 보존의 나이다(ADR-058 결정 4). 이 엔티티가 그
 * 칸을 쓰는 유일한 자리이고, <strong>값이 바뀐 반영만</strong> 시각을 옮긴다. 바뀌지 않은 배송을 다시
 * 저장하는 것은 사건이 아니고, 그때 시각을 옮기면 JPA 가 바뀐 것 없는 행에 UPDATE 를 보낸다.
 */
@Entity
@Table(name = "shipments")
public class ShipmentEntity {

    @Id
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "route_id", nullable = false)
    private UUID routeId;

    @Column(name = "stop_seq", nullable = false)
    private short stopSeq;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ShipmentStatus status;

    @Column(name = "planned_arrival", nullable = false)
    private Instant plannedArrival;

    @Column(name = "eta_at", nullable = false)
    private Instant etaAt;

    @Column(name = "promised_end", nullable = false)
    private Instant promisedEnd;

    @Column(name = "delivered_at")
    private @Nullable Instant deliveredAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ShipmentEntity() {
    }

    /**
     * 도메인에서 새 행을 만든다.
     *
     * @param shipment  배송
     * @param touchedAt 이 쓰기의 시각 — 주입된 시계에서 온다 (불변규칙 12)
     * @return 새 엔티티
     */
    public static ShipmentEntity from(Shipment shipment, Instant touchedAt) {
        ShipmentEntity entity = new ShipmentEntity();
        entity.orderId = shipment.orderId();
        entity.copy(shipment);
        entity.updatedAt = Objects.requireNonNull(touchedAt, "touchedAt");
        return entity;
    }

    /**
     * 도메인으로 되살린다.
     *
     * @return 배송
     */
    public Shipment toDomain() {
        return Shipment.restore(orderId, routeId, stopSeq, status,
                plannedArrival, etaAt, promisedEnd, deliveredAt, version);
    }

    /**
     * 바뀐 값을 반영한다. {@code order_id} 는 PK 라 건드리지 않는다.
     *
     * @param shipment  같은 주문의 배송
     * @param touchedAt 이 쓰기의 시각 — 값이 바뀌었을 때만 {@code updated_at} 이 된다
     * @return 바뀐 값이 있었으면 {@code true}
     */
    public boolean apply(Shipment shipment, Instant touchedAt) {
        Objects.requireNonNull(touchedAt, "touchedAt");
        if (!orderId.equals(shipment.orderId())) {
            throw new IllegalArgumentException(
                    "다른 주문의 배송은 반영하지 않습니다: %s ≠ %s".formatted(orderId, shipment.orderId()));
        }
        if (sameAs(shipment)) {
            return false;
        }
        copy(shipment);
        this.updatedAt = touchedAt;
        return true;
    }

    private boolean sameAs(Shipment shipment) {
        return routeId.equals(shipment.routeId())
                && stopSeq == (short) shipment.stopSeq()
                && status == shipment.status()
                && plannedArrival.equals(shipment.plannedArrival())
                && etaAt.equals(shipment.etaAt())
                && promisedEnd.equals(shipment.promisedEnd())
                && Objects.equals(deliveredAt, shipment.deliveredAt());
    }

    private void copy(Shipment shipment) {
        this.routeId = shipment.routeId();
        this.stopSeq = (short) shipment.stopSeq();
        this.status = shipment.status();
        this.plannedArrival = shipment.plannedArrival();
        this.etaAt = shipment.etaAt();
        this.promisedEnd = shipment.promisedEnd();
        this.deliveredAt = shipment.deliveredAt();
    }

    /** 주문 id. */
    public UUID orderId() {
        return orderId;
    }

    /** 값이 바뀐 마지막 쓰기의 시각 (보존의 나이). */
    public Instant updatedAt() {
        return updatedAt;
    }
}
