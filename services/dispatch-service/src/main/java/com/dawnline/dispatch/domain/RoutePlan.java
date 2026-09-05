package com.dawnline.dispatch.domain;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Money;
import com.dawnline.common.error.ValidationException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 웨이브 하나에 대한 계획 애그리거트 (DESIGN.md §5.3 {@code route_plans}).
 *
 * <p>{@code wave_id} 가 UNIQUE 라 <strong>웨이브당 계획은 하나</strong>다. {@code wave.closed}
 * 가 중복 도착해도 두 번째는 기존 계획을 발견하고 끝난다(§5.3) — 멱등 소비자(불변규칙 2)와
 * 함께 두 겹이다.
 *
 * <p>상태 전이는 이 클래스의 메서드로만 한다(불변규칙 6).
 */
public final class RoutePlan {

    private final UUID id;
    private final UUID waveId;
    private final UUID campId;
    /**
     * 캠프 좌표. {@code wave.closed} 의 스냅샷이다(불변규칙 4).
     *
     * <p>계획 행에 남기는 이유는 <strong>이벤트가 없는 자리에서도 다시 돌아야 하기</strong>
     * 때문이다 — 정체 회수(§5.3), 운영자 재실행, 부분 재계획(§6.8)은 {@code wave.closed} 를
     * 다시 받지 않는다.
     */
    private @Nullable GeoPoint depot;

    private PlanStatus status;
    private @Nullable String strategy;
    private @Nullable PlanMode mode;
    private @Nullable Long seed;
    private @Nullable Integer ruleVersion;
    private @Nullable Instant startedAt;
    private @Nullable Instant finishedAt;
    private @Nullable Money totalCost;
    private @Nullable Integer assignedCount;
    private @Nullable Integer unassignedCount;
    private @Nullable Integer planDurationMs;
    private @Nullable String failureReason;
    private long version;

    private RoutePlan(UUID id, UUID waveId, UUID campId, PlanStatus status) {
        this.id = Objects.requireNonNull(id, "id");
        this.waveId = Objects.requireNonNull(waveId, "waveId");
        this.campId = Objects.requireNonNull(campId, "campId");
        this.status = Objects.requireNonNull(status, "status");
    }

    /**
     * 계획을 요청한다.
     *
     * @param id     계획 id (UUIDv7)
     * @param waveId 대상 웨이브
     * @param campId 캠프
     * @param depot  캠프 좌표 ({@code wave.closed} 의 스냅샷)
     */
    public static RoutePlan request(UUID id, UUID waveId, UUID campId, GeoPoint depot) {
        RoutePlan plan = new RoutePlan(id, waveId, campId, PlanStatus.REQUESTED);
        plan.depot = Objects.requireNonNull(depot, "depot");
        return plan;
    }

    /** 저장된 상태에서 되살린다. */
    public static RoutePlan rehydrate(UUID id, UUID waveId, UUID campId, PlanStatus status,
            @Nullable String strategy, @Nullable PlanMode mode, @Nullable Long seed,
            @Nullable Integer ruleVersion, @Nullable Instant startedAt, @Nullable Instant finishedAt,
            @Nullable Money totalCost, @Nullable Integer assignedCount,
            @Nullable Integer unassignedCount, @Nullable Integer planDurationMs,
            @Nullable String failureReason, @Nullable GeoPoint depot, long version) {

        RoutePlan plan = new RoutePlan(id, waveId, campId, status);
        plan.depot = depot;
        plan.strategy = strategy;
        plan.mode = mode;
        plan.seed = seed;
        plan.ruleVersion = ruleVersion;
        plan.startedAt = startedAt;
        plan.finishedAt = finishedAt;
        plan.totalCost = totalCost;
        plan.assignedCount = assignedCount;
        plan.unassignedCount = unassignedCount;
        plan.planDurationMs = planDurationMs;
        plan.failureReason = failureReason;
        plan.version = version;
        return plan;
    }

    /**
     * 실행을 시작한다.
     *
     * @param strategy    전략 이름
     * @param mode        실행 모드
     * @param seed        난수 seed (같으면 같은 결과, 불변규칙 12)
     * @param ruleVersion 시작 시점의 룰 버전. 진행 중 계획은 이 스냅샷을 쓴다 (§6.3)
     * @param at          시작 시각
     */
    public void begin(String strategy, PlanMode mode, long seed, int ruleVersion, Instant at) {
        status = status.transitionTo(PlanStatus.PLANNING);
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.seed = seed;
        this.ruleVersion = ruleVersion;
        this.startedAt = Objects.requireNonNull(at, "at");
        this.failureReason = null;
    }

    /**
     * 결과를 담는다. 아직 발행하지 않았다.
     *
     * @param totalCost       총비용
     * @param assignedCount   배정된 주문 수
     * @param unassignedCount 미배정 주문 수
     * @param planDurationMs  계획에 걸린 시간(ms)
     * @param at              완료 시각
     */
    public void complete(Money totalCost, int assignedCount, int unassignedCount,
            int planDurationMs, Instant at) {

        status = status.transitionTo(PlanStatus.PLANNED);
        this.totalCost = Objects.requireNonNull(totalCost, "totalCost");
        if (assignedCount < 0 || unassignedCount < 0 || planDurationMs < 0) {
            throw ValidationException.field("counts", assignedCount + "/" + unassignedCount,
                    "계획 결과 수치는 음수일 수 없습니다");
        }
        this.assignedCount = assignedCount;
        this.unassignedCount = unassignedCount;
        this.planDurationMs = planDurationMs;
        this.finishedAt = Objects.requireNonNull(at, "at");
    }

    /**
     * 발행까지 끝났다 (§5.3 — 세 이벤트가 같은 outbox 트랜잭션에 들어간 뒤).
     *
     * @param at 발행 시각
     */
    public void publish(Instant at) {
        status = status.transitionTo(PlanStatus.PUBLISHED);
        this.finishedAt = Objects.requireNonNull(at, "at");
    }

    /**
     * 실패한다. 운영자가 재실행할 수 있다.
     *
     * @param reason {@code plan.failed.reason}
     * @param at     실패 시각
     */
    public void fail(String reason, Instant at) {
        status = status.transitionTo(PlanStatus.FAILED);
        this.failureReason = Objects.requireNonNull(reason, "reason");
        this.finishedAt = Objects.requireNonNull(at, "at");
    }

    /**
     * 죽은 인스턴스가 남긴 {@code PLANNING} 을 되돌린다 (§5.3 정체 회수).
     *
     * @param at 회수 시각
     */
    public void requeue(Instant at) {
        status = status.transitionTo(PlanStatus.REQUESTED);
        this.startedAt = null;
        this.finishedAt = Objects.requireNonNull(at, "at");
    }

    /** 계획 id. */
    public UUID id() {
        return id;
    }

    /** 대상 웨이브. */
    public UUID waveId() {
        return waveId;
    }

    /** 캠프. */
    public UUID campId() {
        return campId;
    }

    /**
     * 캠프 좌표. 이 컬럼이 생기기 전의 행이면 비어 있고, 그 계획은 다시 돌릴 수 없다.
     */
    public Optional<GeoPoint> depot() {
        return Optional.ofNullable(depot);
    }

    /** 현재 상태. */
    public PlanStatus status() {
        return status;
    }

    /** 전략 이름. */
    public Optional<String> strategy() {
        return Optional.ofNullable(strategy);
    }

    /** 실행 모드. */
    public Optional<PlanMode> mode() {
        return Optional.ofNullable(mode);
    }

    /** 난수 seed. */
    public Optional<Long> seed() {
        return Optional.ofNullable(seed);
    }

    /** 시작 시점의 룰 버전. */
    public Optional<Integer> ruleVersion() {
        return Optional.ofNullable(ruleVersion);
    }

    /** 시작 시각. */
    public Optional<Instant> startedAt() {
        return Optional.ofNullable(startedAt);
    }

    /** 종료 시각. */
    public Optional<Instant> finishedAt() {
        return Optional.ofNullable(finishedAt);
    }

    /** 총비용. */
    public Optional<Money> totalCost() {
        return Optional.ofNullable(totalCost);
    }

    /** 배정된 주문 수. */
    public Optional<Integer> assignedCount() {
        return Optional.ofNullable(assignedCount);
    }

    /** 미배정 주문 수. */
    public Optional<Integer> unassignedCount() {
        return Optional.ofNullable(unassignedCount);
    }

    /** 계획에 걸린 시간(ms). */
    public Optional<Integer> planDurationMs() {
        return Optional.ofNullable(planDurationMs);
    }

    /** 실패 사유. */
    public Optional<String> failureReason() {
        return Optional.ofNullable(failureReason);
    }

    /** 낙관적 락 버전. */
    public long version() {
        return version;
    }
}
