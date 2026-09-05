package com.dawnline.dispatch.adapter.out.persistence;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.domain.CandidateStatus;
import com.dawnline.dispatch.domain.DispatchCandidate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code dispatch_candidates} 행 (DESIGN.md §5.3).
 *
 * <p>좌표를 {@code NUMERIC(9,6)} 으로 저장하므로 {@link BigDecimal} 로 왕복한다(불변규칙 9).
 * {@code geohash7} 은 좌표에서 파생되지만 컬럼으로 둔다 — stop 통합 키라 질의로 묶을 수 있어야
 * 하고, 파생값을 매번 계산하면 인덱스를 걸 수 없다.
 */
@Entity
@Table(name = "dispatch_candidates")
public class DispatchCandidateEntity {

    private static final int COORDINATE_SCALE = 6;

    @Id
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "wave_id", nullable = false)
    private UUID waveId;

    @Column(name = "camp_id", nullable = false)
    private UUID campId;

    @Column(name = "zone_id")
    private @Nullable UUID zoneId;

    @Column(name = "lat", nullable = false, precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(name = "lng", nullable = false, precision = 9, scale = 6)
    private BigDecimal lng;

    /**
     * {@code CHAR(7)} 이라 JDBC 타입을 명시한다. {@code length} 만으로는 Hibernate 가 VARCHAR 로
     * 검증해 기동에서 깨진다 — order-service 가 같은 컬럼에서 같은 것을 겪었다.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "geohash7", nullable = false, length = 7)
    private String geohash7;

    @Column(name = "weight_g", nullable = false)
    private int weightG;

    @Column(name = "volume_cm3", nullable = false)
    private int volumeCm3;

    @Column(name = "requires_cold", nullable = false)
    private boolean requiresCold;

    @Column(name = "hazmat", nullable = false)
    private boolean hazmat;

    @Column(name = "promised_start", nullable = false)
    private Instant promisedStart;

    @Column(name = "promised_end", nullable = false)
    private Instant promisedEnd;

    @Column(name = "service_seconds", nullable = false)
    private int serviceSeconds;

    @Column(name = "priority", nullable = false)
    private short priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CandidateStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected DispatchCandidateEntity() {
    }

    /**
     * 도메인에서 새 행을 만든다.
     *
     * @param candidate 후보
     */
    public static DispatchCandidateEntity from(DispatchCandidate candidate) {
        DispatchCandidateEntity entity = new DispatchCandidateEntity();
        entity.orderId = candidate.orderId();
        entity.waveId = candidate.waveId();
        entity.campId = candidate.campId();
        entity.zoneId = candidate.zoneId().orElse(null);
        entity.lat = coordinate(candidate.location().lat());
        entity.lng = coordinate(candidate.location().lng());
        entity.geohash7 = candidate.location().geohash7();
        entity.weightG = candidate.weightG();
        entity.volumeCm3 = candidate.volumeCm3();
        entity.requiresCold = candidate.requiresCold();
        entity.hazmat = candidate.hazmat();
        entity.promisedStart = candidate.promised().start();
        entity.promisedEnd = candidate.promised().end();
        entity.serviceSeconds = candidate.serviceSeconds();
        entity.priority = (short) candidate.priority();
        entity.status = candidate.status();
        entity.createdAt = candidate.createdAt();
        entity.updatedAt = candidate.updatedAt();
        return entity;
    }

    /** 도메인으로 되살린다. */
    public DispatchCandidate toDomain() {
        // CHAR 은 오른쪽이 공백으로 채워져 돌아온다. 파생값이라 도메인에는 넣지 않지만,
        // 여기서 자르지 않으면 이 필드를 읽는 다음 사람이 같은 함정에 빠진다.
        return DispatchCandidate.rehydrate(orderId, waveId, campId, zoneId,
                GeoPoint.of(lat.doubleValue(), lng.doubleValue()), weightG, volumeCm3,
                requiresCold, hazmat, new TimeWindow(promisedStart, promisedEnd), serviceSeconds,
                priority, status, createdAt, updatedAt, version);
    }

    /**
     * 상태 전이만 반영한다. 스냅샷 컬럼은 건드리지 않는다 — 계획의 근거가 바뀌면 안 된다.
     *
     * @param candidate 같은 주문의 후보
     */
    public void apply(DispatchCandidate candidate) {
        if (!orderId.equals(candidate.orderId())) {
            throw new IllegalArgumentException(
                    "다른 주문의 상태는 반영하지 않습니다: %s ≠ %s".formatted(orderId, candidate.orderId()));
        }
        this.status = candidate.status();
        this.updatedAt = candidate.updatedAt();
    }

    /** 주문 id. */
    public UUID orderId() {
        return orderId;
    }

    private static BigDecimal coordinate(double value) {
        return BigDecimal.valueOf(value).setScale(COORDINATE_SCALE, java.math.RoundingMode.HALF_UP);
    }
}
