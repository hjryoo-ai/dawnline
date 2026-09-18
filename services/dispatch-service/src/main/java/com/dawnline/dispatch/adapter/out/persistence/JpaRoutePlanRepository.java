package com.dawnline.dispatch.adapter.out.persistence;

import com.dawnline.dispatch.application.port.out.RoutePlanRepository;
import com.dawnline.dispatch.domain.RoutePlan;
import jakarta.persistence.EntityManager;
import java.time.Duration;
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

    /**
     * 이 캠프의 마지막 발행 계획이 쓴 알고리즘 시간 (§6.7).
     *
     * <p>{@code status} 를 <strong>리터럴</strong>로 적는다(CLAUDE.md 코딩 컨벤션). 그리고
     * 정렬 기준이 {@code finished_at} 인 이유는 <em>발행 순서</em>가 알고 싶은 것이기 때문이다 —
     * {@code started_at} 으로 정렬하면 오래 걸린 계획이 그 뒤에 끝난 짧은 계획보다 뒤에 온다.
     */
    private static final String LAST_PUBLISHED_SQL = """
            SELECT plan_duration_ms FROM route_plans
             WHERE camp_id = ? AND status = 'PUBLISHED' AND plan_duration_ms IS NOT NULL
             ORDER BY finished_at DESC
             LIMIT 1
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
    public Optional<Duration> lastPublishedDuration(UUID campId) {
        Objects.requireNonNull(campId, "campId");
        // 네이티브 질의라 실행 전에 auto-flush 가 돈다(ADR-029). 이 시점 세션에 떠 있는 것은
        // 계획 엔티티 하나뿐이다 — 후보는 읽기 전용 프로젝션으로 읽으므로 관리 대상이 아니다.
        // 그 사실이 깨지면 20초짜리 더티 체크가 여기로 돌아온다.
        List<?> rows = entityManager.createNativeQuery(LAST_PUBLISHED_SQL)
                .setParameter(1, campId)
                .getResultList();
        return rows.isEmpty() ? Optional.empty()
                : Optional.of(Duration.ofMillis(((Number) rows.getFirst()).longValue()));
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
