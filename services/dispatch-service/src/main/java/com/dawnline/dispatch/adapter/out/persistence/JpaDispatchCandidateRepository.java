package com.dawnline.dispatch.adapter.out.persistence;

import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.domain.DispatchCandidate;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code dispatch_candidates} 어댑터 (DESIGN.md §5.3).
 *
 * <h2>{@code insertIfAbsent} 를 네이티브 SQL 로 적는 이유</h2>
 * {@code ON CONFLICT DO NOTHING} 은 JPQL 에 없다. 조회 후 저장으로 흉내 내면 두 소비자가 같은
 * 주문을 동시에 받았을 때 둘 다 "없다" 를 보고 둘 다 넣는다 — 하나는 PK 위반으로 죽고, 그
 * 재시도가 DLQ 로 간다. fulfillment 가 같은 이유로 같은 모양을 쓴다(ADR-018).
 */
public class JpaDispatchCandidateRepository implements DispatchCandidateRepository {

    private static final String INSERT_SQL = """
            INSERT INTO dispatch_candidates (
                order_id, wave_id, camp_id, zone_id, lat, lng, geohash7,
                weight_g, volume_cm3, requires_cold, hazmat,
                promised_start, promised_end, service_seconds, priority,
                status, version, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
            ON CONFLICT (order_id) DO NOTHING
            """;

    /**
     * 술어를 <strong>리터럴로</strong> 적는다. 바인드 파라미터로 넣으면 플래너가 일반 계획에서
     * 술어를 증명하지 못해 {@code ix_cand_wave (wave_id, status)} 의 뒤 컬럼을 못 쓴다
     * (CLAUDE.md 코딩 컨벤션).
     */
    private static final String FIND_PLANNABLE_JPQL = """
            SELECT c FROM DispatchCandidateEntity c
             WHERE c.waveId = :waveId AND c.status = com.dawnline.dispatch.domain.CandidateStatus.PENDING
             ORDER BY c.orderId
            """;

    private final EntityManager entityManager;

    /**
     * @param entityManager 공유 EntityManager 프록시
     */
    public JpaDispatchCandidateRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    }

    @Override
    public boolean insertIfAbsent(DispatchCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        int inserted = entityManager.createNativeQuery(INSERT_SQL)
                .setParameter(1, candidate.orderId())
                .setParameter(2, candidate.waveId())
                .setParameter(3, candidate.campId())
                .setParameter(4, candidate.zoneId().orElse(null))
                .setParameter(5, candidate.location().lat())
                .setParameter(6, candidate.location().lng())
                .setParameter(7, candidate.location().geohash7())
                .setParameter(8, candidate.weightG())
                .setParameter(9, candidate.volumeCm3())
                .setParameter(10, candidate.requiresCold())
                .setParameter(11, candidate.hazmat())
                .setParameter(12, candidate.promised().start())
                .setParameter(13, candidate.promised().end())
                .setParameter(14, candidate.serviceSeconds())
                .setParameter(15, (short) candidate.priority())
                .setParameter(16, candidate.status().name())
                .setParameter(17, candidate.createdAt())
                .setParameter(18, candidate.updatedAt())
                .executeUpdate();
        return inserted > 0;
    }

    @Override
    public Optional<DispatchCandidate> findById(UUID orderId) {
        return Optional.ofNullable(entityManager.find(DispatchCandidateEntity.class, orderId))
                .map(DispatchCandidateEntity::toDomain);
    }

    @Override
    public List<DispatchCandidate> findPlannableInWave(UUID waveId) {
        return entityManager.createQuery(FIND_PLANNABLE_JPQL, DispatchCandidateEntity.class)
                .setParameter("waveId", waveId)
                .getResultList().stream()
                .map(DispatchCandidateEntity::toDomain)
                .toList();
    }

    @Override
    public void update(DispatchCandidate candidate) {
        DispatchCandidateEntity entity =
                entityManager.find(DispatchCandidateEntity.class, candidate.orderId());
        if (entity == null) {
            throw new IllegalStateException("없는 후보를 갱신할 수 없습니다: " + candidate.orderId());
        }
        entity.apply(candidate);
    }
}
