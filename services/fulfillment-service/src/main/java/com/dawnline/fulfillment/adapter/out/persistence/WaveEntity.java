package com.dawnline.fulfillment.adapter.out.persistence;

import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.Wave;
import com.dawnline.fulfillment.domain.WaveStatus;
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
 * {@code waves} 행 (DESIGN.md §5.2).
 *
 * <p>도메인 {@link Wave} 와 분리된 별도 클래스다 (ADR-007) — 도메인은 프레임워크 비의존이어야
 * 하고(불변규칙 5) 세터가 없어야 하는데(불변규칙 6) JPA 는 가변 필드와 무인자 생성자를 요구한다.
 */
@Entity
@Table(name = "waves")
public class WaveEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "camp_id", nullable = false)
    private UUID campId;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_tier", nullable = false, length = 16)
    private ServiceTier serviceTier;

    @Column(name = "cutoff_at", nullable = false)
    private Instant cutoffAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private WaveStatus status;

    @Column(name = "order_count", nullable = false)
    private int orderCount;

    @Column(name = "closed_at")
    private @Nullable Instant closedAt;

    /** 낙관적 락. {@code @Version} 이 붙어 있으므로 Hibernate 가 증가시킨다. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** JPA 전용. */
    protected WaveEntity() {
    }

    /**
     * 도메인 애그리거트를 행으로 옮긴다.
     *
     * @param wave 도메인 웨이브
     */
    public static WaveEntity from(Wave wave) {
        WaveEntity entity = new WaveEntity();
        entity.id = wave.id();
        entity.campId = wave.campId();
        entity.serviceTier = wave.serviceTier();
        entity.cutoffAt = wave.cutoffAt();
        entity.applyStateOf(wave);
        return entity;
    }

    /**
     * 이미 저장된 행에 도메인의 변경을 반영한다.
     *
     * <p>관리 인스턴스를 고치는 이유는 낙관적 락이다. {@code merge} 로 새 인스턴스를 밀어 넣으면
     * 도메인이 들고 있던(=읽은 시점의) 버전이 기준이 되어, 그 사이의 변경이 충돌로 잡히지 않는다.
     *
     * <p>불변 필드(id·campId·serviceTier·cutoffAt)는 건드리지 않는다. 그 셋이 바뀌면 다른
     * 웨이브다 — 자연키이기 때문이다.
     *
     * @param wave 변경된 도메인 웨이브
     */
    public void applyStateOf(Wave wave) {
        if (id != null && !id.equals(wave.id())) {
            throw new IllegalArgumentException("다른 웨이브의 상태를 반영할 수 없습니다: " + id + " ← " + wave.id());
        }
        this.status = wave.status();
        this.orderCount = wave.orderCount();
        this.closedAt = wave.closedAt();
    }

    /** 행을 도메인 애그리거트로 되살린다. */
    public Wave toDomain() {
        return Wave.rehydrate(id, campId, serviceTier, cutoffAt, status, orderCount, closedAt, version);
    }

    /** 웨이브 id. */
    public UUID id() {
        return id;
    }

    /** 현재 상태. */
    public WaveStatus status() {
        return status;
    }

    /** 낙관적 락 버전. */
    public long version() {
        return version;
    }
}
