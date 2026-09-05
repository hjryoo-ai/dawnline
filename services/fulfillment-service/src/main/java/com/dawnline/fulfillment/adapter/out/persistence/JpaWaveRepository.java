package com.dawnline.fulfillment.adapter.out.persistence;

import com.dawnline.common.error.NotFoundException;
import com.dawnline.fulfillment.application.port.out.WaveRepository;
import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.Wave;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link WaveRepository} 의 JPA 구현 (DESIGN.md §5.2).
 *
 * <p>세 곳에서 네이티브 SQL 을 쓴다. 기준은 {@code libs/messaging} 과 같다 — <strong>생성되는
 * 계획이 정확성이나 성능의 핵심인 자리</strong>는 SQL 이 소스에 그대로 보여야 한다.
 * {@code ON CONFLICT DO NOTHING}(JPQL 에 없다), 마감 대상 조회(부분 인덱스), 보존 정리
 * ({@code ctid} 배치)가 그렇다.
 */
public class JpaWaveRepository implements WaveRepository {

    private static final String INSERT_SQL = """
            INSERT INTO waves (id, camp_id, service_tier, cutoff_at, status, order_count, closed_at, version)
            VALUES (:id, :campId, :serviceTier, :cutoffAt, :status, :orderCount, :closedAt, 0)
            ON CONFLICT (camp_id, service_tier, cutoff_at) DO NOTHING
            """;

    /**
     * 마감 대상 조회 (컷오프 스케줄러, 30초 주기).
     *
     * <p><strong>{@code status = 'OPEN'} 을 파라미터가 아니라 리터럴로 둔다.</strong>
     * {@code ix_waves_open_cutoff} 는 {@code WHERE status='OPEN'} 부분 인덱스인데, 값이 바인드
     * 파라미터로 들어오면 플래너가 일반 계획(generic plan)을 고를 때 그 술어를 만족한다고 증명할
     * 수 없어 인덱스를 못 탄다. 상수는 상수로 적어야 그 인덱스가 값을 한다.
     *
     * <p>{@code cutoff_at <= :threshold} 의 {@code threshold} 는 호출자가 {@code now − grace} 로
     * 계산해 넘긴다 (ADR-020 결정 2).
     */
    private static final String FIND_DUE_SQL = """
            SELECT * FROM waves
             WHERE status = 'OPEN' AND cutoff_at <= :threshold
             ORDER BY cutoff_at
             LIMIT :limit
            """;

    /**
     * 보존 만료 삭제 (ADR-023 결정 3 — 90일).
     *
     * <p>{@code NOT EXISTS} 로 참조 행이 없는 것만 지운다. ADR-023 은 두 보존 기간(30일·90일) 때문에
     * FK 가 자연히 만족된다고 적었지만, 그것은 <em>그렇게 고른 결과</em>이지 강제되는 성질이 아니다.
     * 가드가 없으면 어느 한쪽 기간이 바뀌는 날 정리 배치가 매일 FK 위반으로 죽는다.
     *
     * <p>비용은 {@code ix_fulfillment_orders_wave} 가 낸다. 그 인덱스가 <strong>부분</strong>
     * 이었을 때는 FK 검사와 이 안티조인이 모두 전수 스캔이 되어 40건 삭제에 7초가 걸렸다
     * (docs/benchmarks/phase2-fulfillment-orders-indexes.md §3).
     */
    private static final String DELETE_EXPIRED_SQL = """
            DELETE FROM waves
             WHERE ctid IN (
                   SELECT w.ctid FROM waves w
                    WHERE w.closed_at < :threshold
                      AND w.status IN ('PLANNED', 'PLAN_FAILED')
                      AND NOT EXISTS (SELECT 1 FROM fulfillment_orders fo WHERE fo.wave_id = w.id)
                    ORDER BY w.closed_at
                    LIMIT :limit)
            """;

    private final EntityManager entityManager;

    /**
     * @param entityManager 공유 EntityManager 프록시
     */
    public JpaWaveRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    }

    @Override
    public boolean insertIfAbsent(Wave wave) {
        Objects.requireNonNull(wave, "wave");
        int inserted = entityManager.createNativeQuery(INSERT_SQL)
                .setParameter("id", wave.id())
                .setParameter("campId", wave.campId())
                .setParameter("serviceTier", wave.serviceTier().name())
                .setParameter("cutoffAt", wave.cutoffAt())
                .setParameter("status", wave.status().name())
                .setParameter("orderCount", wave.orderCount())
                .setParameter("closedAt", wave.closedAt())
                .executeUpdate();
        return inserted == 1;
    }

    @Override
    public Optional<Wave> findByNaturalKey(UUID campId, ServiceTier serviceTier, Instant cutoffAt) {
        Objects.requireNonNull(campId, "campId");
        Objects.requireNonNull(serviceTier, "serviceTier");
        Objects.requireNonNull(cutoffAt, "cutoffAt");
        return entityManager.createQuery("""
                        SELECT w FROM WaveEntity w
                         WHERE w.campId = :campId AND w.serviceTier = :tier AND w.cutoffAt = :cutoffAt""",
                        WaveEntity.class)
                .setParameter("campId", campId)
                .setParameter("tier", serviceTier)
                .setParameter("cutoffAt", cutoffAt)
                .getResultList().stream()
                .findFirst()
                .map(WaveEntity::toDomain);
    }

    @Override
    public Optional<Wave> findById(UUID id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(entityManager.find(WaveEntity.class, id)).map(WaveEntity::toDomain);
    }

    @Override
    public Optional<Wave> findByIdForUpdate(UUID id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(entityManager.find(WaveEntity.class, id, LockModeType.PESSIMISTIC_WRITE))
                .map(WaveEntity::toDomain);
    }

    @Override
    public List<Wave> findDueForClosing(Instant cutoffAtOrBefore, int limit) {
        Objects.requireNonNull(cutoffAtOrBefore, "cutoffAtOrBefore");
        requirePositive(limit);
        @SuppressWarnings("unchecked")
        List<WaveEntity> rows = entityManager.createNativeQuery(FIND_DUE_SQL, WaveEntity.class)
                .setParameter("threshold", cutoffAtOrBefore)
                .setParameter("limit", limit)
                .getResultList();
        List<Wave> waves = new ArrayList<>(rows.size());
        for (WaveEntity row : rows) {
            waves.add(row.toDomain());
        }
        return List.copyOf(waves);
    }

    @Override
    public void update(Wave wave) {
        Objects.requireNonNull(wave, "wave");
        WaveEntity entity = entityManager.find(WaveEntity.class, wave.id());
        if (entity == null) {
            throw NotFoundException.of("Wave", wave.id());
        }
        entity.applyStateOf(wave);
    }

    @Override
    public int deleteSettledClosedBefore(Instant closedBefore, int limit) {
        Objects.requireNonNull(closedBefore, "closedBefore");
        requirePositive(limit);
        return entityManager.createNativeQuery(DELETE_EXPIRED_SQL)
                .setParameter("threshold", closedBefore)
                .setParameter("limit", limit)
                .executeUpdate();
    }

    private static void requirePositive(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit 은 1 이상이어야 합니다: " + limit);
        }
    }
}
