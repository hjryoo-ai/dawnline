package com.dawnline.dispatch.adapter.out.persistence;

import com.dawnline.dispatch.application.port.out.RoutePlanRepository;
import com.dawnline.dispatch.domain.RoutePlan;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code route_plans} 어댑터 (DESIGN.md §5.3).
 *
 * <p>{@code insertIfAbsent} 가 {@code ON CONFLICT (wave_id) DO NOTHING} 인 것이 §5.3 의 멱등을
 * 만든다 — 두 소비자가 같은 {@code wave.closed} 를 동시에 받아도 계획은 하나다.
 */
public class JpaRoutePlanRepository implements RoutePlanRepository {

    private static final String INSERT_SQL = """
            INSERT INTO route_plans (id, wave_id, camp_id, status, depot_lat, depot_lng, version)
            VALUES (?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT (wave_id) DO NOTHING
            """;

    /** 술어를 리터럴로 적는다 (CLAUDE.md 코딩 컨벤션). */
    private static final String STALE_JPQL = """
            SELECT p FROM RoutePlanEntity p
             WHERE p.status = com.dawnline.dispatch.domain.PlanStatus.PLANNING
               AND p.startedAt < :before
             ORDER BY p.startedAt
            """;

    private final EntityManager entityManager;

    /**
     * @param entityManager 공유 EntityManager 프록시
     */
    public JpaRoutePlanRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    }

    @Override
    public boolean insertIfAbsent(RoutePlan plan) {
        Objects.requireNonNull(plan, "plan");
        return entityManager.createNativeQuery(INSERT_SQL)
                .setParameter(1, plan.id())
                .setParameter(2, plan.waveId())
                .setParameter(3, plan.campId())
                .setParameter(4, plan.status().name())
                .setParameter(5, plan.depot().map(com.dawnline.common.GeoPoint::lat).orElse(null))
                .setParameter(6, plan.depot().map(com.dawnline.common.GeoPoint::lng).orElse(null))
                .executeUpdate() > 0;
    }

    @Override
    public Optional<RoutePlan> findByWaveId(UUID waveId) {
        return entityManager
                .createQuery("SELECT p FROM RoutePlanEntity p WHERE p.waveId = :waveId",
                        RoutePlanEntity.class)
                .setParameter("waveId", waveId)
                .getResultStream().findFirst()
                .map(RoutePlanEntity::toDomain);
    }

    @Override
    public Optional<RoutePlan> findById(UUID planId) {
        return Optional.ofNullable(entityManager.find(RoutePlanEntity.class, planId))
                .map(RoutePlanEntity::toDomain);
    }

    @Override
    public List<RoutePlan> findStalePlanning(Instant startedBefore, int limit) {
        return entityManager.createQuery(STALE_JPQL, RoutePlanEntity.class)
                .setParameter("before", startedBefore)
                .setMaxResults(limit)
                .getResultList().stream()
                .map(RoutePlanEntity::toDomain)
                .toList();
    }

    @Override
    public void update(RoutePlan plan) {
        RoutePlanEntity entity = entityManager.find(RoutePlanEntity.class, plan.id());
        if (entity == null) {
            throw new IllegalStateException("없는 계획을 갱신할 수 없습니다: " + plan.id());
        }
        entity.apply(plan);
    }
}
