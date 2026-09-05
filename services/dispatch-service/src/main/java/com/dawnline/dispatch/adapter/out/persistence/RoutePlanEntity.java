package com.dawnline.dispatch.adapter.out.persistence;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Money;
import com.dawnline.dispatch.domain.PlanMode;
import com.dawnline.dispatch.domain.PlanStatus;
import com.dawnline.dispatch.domain.RoutePlan;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** {@code route_plans} 행 (DESIGN.md §5.3). */
@Entity
@Table(name = "route_plans")
public class RoutePlanEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "wave_id", nullable = false, unique = true)
    private UUID waveId;

    @Column(name = "camp_id", nullable = false)
    private UUID campId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PlanStatus status;

    @Column(name = "strategy", length = 32)
    private @Nullable String strategy;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", length = 8)
    private @Nullable PlanMode mode;

    @Column(name = "seed")
    private @Nullable Long seed;

    @Column(name = "rule_version")
    private @Nullable Integer ruleVersion;

    @Column(name = "started_at")
    private @Nullable Instant startedAt;

    @Column(name = "finished_at")
    private @Nullable Instant finishedAt;

    @Column(name = "total_cost_krw")
    private @Nullable Long totalCostKrw;

    @Column(name = "assigned_count")
    private @Nullable Integer assignedCount;

    @Column(name = "unassigned_count")
    private @Nullable Integer unassignedCount;

    @Column(name = "plan_duration_ms")
    private @Nullable Integer planDurationMs;

    @Column(name = "failure_reason", length = 32)
    private @Nullable String failureReason;

    /** {@code wave.closed} 의 depot 스냅샷 (V2). 이벤트가 없는 재실행이 이 값을 쓴다. */
    @Column(name = "depot_lat", precision = 9, scale = 6)
    private @Nullable BigDecimal depotLat;

    @Column(name = "depot_lng", precision = 9, scale = 6)
    private @Nullable BigDecimal depotLng;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected RoutePlanEntity() {
    }

    /** 도메인으로 되살린다. */
    public RoutePlan toDomain() {
        GeoPoint depot = depotLat == null || depotLng == null ? null
                : GeoPoint.of(depotLat.doubleValue(), depotLng.doubleValue());
        return RoutePlan.rehydrate(id, waveId, campId, status, strategy, mode, seed, ruleVersion,
                startedAt, finishedAt, totalCostKrw == null ? null : Money.krw(totalCostKrw),
                assignedCount, unassignedCount, planDurationMs, failureReason, depot, version);
    }

    /**
     * 상태와 결과를 반영한다. 자연키({@code wave_id})는 건드리지 않는다.
     *
     * @param plan 같은 계획
     */
    public void apply(RoutePlan plan) {
        if (!id.equals(plan.id())) {
            throw new IllegalArgumentException(
                    "다른 계획의 상태는 반영하지 않습니다: %s ≠ %s".formatted(id, plan.id()));
        }
        this.status = plan.status();
        this.strategy = plan.strategy().orElse(null);
        this.mode = plan.mode().orElse(null);
        this.seed = plan.seed().orElse(null);
        this.ruleVersion = plan.ruleVersion().orElse(null);
        this.startedAt = plan.startedAt().orElse(null);
        this.finishedAt = plan.finishedAt().orElse(null);
        this.totalCostKrw = plan.totalCost().map(Money::krw).orElse(null);
        this.assignedCount = plan.assignedCount().orElse(null);
        this.unassignedCount = plan.unassignedCount().orElse(null);
        this.planDurationMs = plan.planDurationMs().orElse(null);
        this.failureReason = plan.failureReason().orElse(null);
        plan.depot().ifPresent(depot -> {
            this.depotLat = coordinate(depot.lat());
            this.depotLng = coordinate(depot.lng());
        });
    }

    private static BigDecimal coordinate(double value) {
        return BigDecimal.valueOf(value).setScale(6, java.math.RoundingMode.HALF_UP);
    }
}
