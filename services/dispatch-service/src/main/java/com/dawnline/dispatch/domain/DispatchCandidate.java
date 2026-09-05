package com.dawnline.dispatch.domain;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.TimeWindow;
import com.dawnline.common.error.ValidationException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 계획 후보 애그리거트 (DESIGN.md §5.3 {@code dispatch_candidates}).
 *
 * <p>{@code fulfillment.planned} 의 스냅샷이다 — 계획에 필요한 것을 전부 들고 있어야 한다
 * (불변규칙 4 — 코어 서비스 간 동기 호출 금지). 그래서 주소·화물·약속창이 여기 복사돼 있고,
 * fulfillment 에 되묻지 않는다.
 *
 * <p>상태 전이는 이 클래스의 메서드로만 한다(불변규칙 6). 세터로 status 를 바꾸지 않는다.
 */
public final class DispatchCandidate {

    private final UUID orderId;
    private final UUID waveId;
    private final UUID campId;
    private final @Nullable UUID zoneId;
    private final GeoPoint location;
    private final int weightG;
    private final int volumeCm3;
    private final boolean requiresCold;
    private final boolean hazmat;
    private final TimeWindow promised;
    private final int serviceSeconds;
    private final int priority;
    private final Instant createdAt;

    private CandidateStatus status;
    private Instant updatedAt;
    private long version;

    private DispatchCandidate(UUID orderId, UUID waveId, UUID campId, @Nullable UUID zoneId,
            GeoPoint location, int weightG, int volumeCm3, boolean requiresCold, boolean hazmat,
            TimeWindow promised, int serviceSeconds, int priority, CandidateStatus status,
            Instant createdAt, Instant updatedAt, long version) {

        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.waveId = Objects.requireNonNull(waveId, "waveId");
        this.campId = Objects.requireNonNull(campId, "campId");
        this.zoneId = zoneId;
        this.location = Objects.requireNonNull(location, "location");
        this.promised = Objects.requireNonNull(promised, "promised");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (weightG < 0 || volumeCm3 < 0) {
            throw ValidationException.field("parcel", weightG + "/" + volumeCm3,
                    "중량·부피는 음수일 수 없습니다");
        }
        if (serviceSeconds < 0) {
            throw ValidationException.field("serviceSeconds", serviceSeconds, "서비스 시간은 음수일 수 없습니다");
        }
        if (priority < 0) {
            throw ValidationException.field("priority", priority, "우선도는 음수일 수 없습니다");
        }
        this.weightG = weightG;
        this.volumeCm3 = volumeCm3;
        this.requiresCold = requiresCold;
        this.hazmat = hazmat;
        this.serviceSeconds = serviceSeconds;
        this.priority = priority;
        this.version = version;
    }

    /**
     * 새로 적재한다.
     *
     * @param at 적재 시각 (주입된 {@code Clock}, 불변규칙 12)
     */
    public static DispatchCandidate load(UUID orderId, UUID waveId, UUID campId,
            @Nullable UUID zoneId, GeoPoint location, int weightG, int volumeCm3,
            boolean requiresCold, boolean hazmat, TimeWindow promised, int serviceSeconds,
            int priority, Instant at) {

        return new DispatchCandidate(orderId, waveId, campId, zoneId, location, weightG, volumeCm3,
                requiresCold, hazmat, promised, serviceSeconds, priority, CandidateStatus.PENDING,
                at, at, 0L);
    }

    /** 저장된 상태에서 되살린다. */
    public static DispatchCandidate rehydrate(UUID orderId, UUID waveId, UUID campId,
            @Nullable UUID zoneId, GeoPoint location, int weightG, int volumeCm3,
            boolean requiresCold, boolean hazmat, TimeWindow promised, int serviceSeconds,
            int priority, CandidateStatus status, Instant createdAt, Instant updatedAt,
            long version) {

        return new DispatchCandidate(orderId, waveId, campId, zoneId, location, weightG, volumeCm3,
                requiresCold, hazmat, promised, serviceSeconds, priority, status, createdAt,
                updatedAt, version);
    }

    /**
     * 계획 결과를 반영한다. 축 규칙대로 <strong>뒤로 가는 전이는 무시</strong>한다.
     *
     * @param target 결과 상태 ({@code PLANNED} 또는 {@code UNASSIGNED})
     * @param at     반영 시각
     * @return 실제로 전이했으면 참, 늦게 온 이벤트라 무시했으면 거짓
     */
    public boolean recordPlanResult(CandidateStatus target, Instant at) {
        if (target != CandidateStatus.PLANNED && target != CandidateStatus.UNASSIGNED) {
            throw ValidationException.field("target", target, "계획 결과는 PLANNED 또는 UNASSIGNED 입니다");
        }
        if (status.hasProgressedPast(target)) {
            return false;
        }
        status = status.transitionTo(target);
        updatedAt = at;
        return true;
    }

    /**
     * 취소한다 (§6.10 첫 분기). 행을 지우지 않는 이유는 ADR-026 에 있다.
     *
     * @param at 취소 시각
     * @return 실제로 전이했으면 참, 이미 취소돼 있었으면 거짓
     */
    public boolean cancel(Instant at) {
        if (status == CandidateStatus.CANCELLED) {
            return false;
        }
        status = status.transitionTo(CandidateStatus.CANCELLED);
        updatedAt = at;
        return true;
    }

    /** 주문 id (PK). */
    public UUID orderId() {
        return orderId;
    }

    /** 소속 웨이브. */
    public UUID waveId() {
        return waveId;
    }

    /** 캠프. */
    public UUID campId() {
        return campId;
    }

    /** 권역. 지오코딩이 실패한 경우 비어 있다. */
    public java.util.Optional<UUID> zoneId() {
        return java.util.Optional.ofNullable(zoneId);
    }

    /** 배송지. */
    public GeoPoint location() {
        return location;
    }

    /** 중량(g). */
    public int weightG() {
        return weightG;
    }

    /** 부피(㎤). */
    public int volumeCm3() {
        return volumeCm3;
    }

    /** 냉장이 필요한가. */
    public boolean requiresCold() {
        return requiresCold;
    }

    /** 위험물인가. */
    public boolean hazmat() {
        return hazmat;
    }

    /** 약속 배송창. */
    public TimeWindow promised() {
        return promised;
    }

    /** 하차·전달 시간(초). */
    public int serviceSeconds() {
        return serviceSeconds;
    }

    /** 우선도. */
    public int priority() {
        return priority;
    }

    /** 현재 상태. */
    public CandidateStatus status() {
        return status;
    }

    /** 적재 시각. */
    public Instant createdAt() {
        return createdAt;
    }

    /** 마지막 변경 시각. */
    public Instant updatedAt() {
        return updatedAt;
    }

    /** 낙관적 락 버전. */
    public long version() {
        return version;
    }
}
