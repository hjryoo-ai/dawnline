package com.dawnline.dispatch.adapter.out.persistence;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.TimeWindow;
import com.dawnline.common.error.ValidationException;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.domain.CandidateStatus;
import com.dawnline.dispatch.domain.DispatchCandidate;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code dispatch_candidates} 어댑터 (DESIGN.md §5.3).
 *
 * <h2>{@code insertIfAbsent} 를 네이티브 SQL 로 적는 이유</h2>
 * {@code ON CONFLICT DO NOTHING} 은 JPQL 에 없다. 조회 후 저장으로 흉내 내면 두 소비자가 같은
 * 주문을 동시에 받았을 때 둘 다 "없다" 를 보고 둘 다 넣는다 — 하나는 PK 위반으로 죽고, 그
 * 재시도가 DLQ 로 간다. fulfillment 가 같은 이유로 같은 모양을 쓴다(ADR-018).
 *
 * <h2>두 갈래인 이유</h2>
 * 단건 경로({@code findById}·{@code update})는 {@code EntityManager} 를 그대로 쓴다 — 취소처럼
 * 애그리거트 하나를 상태 머신으로 옮기는 자리다. <strong>계획 경로는 {@code JdbcTemplate} 로
 * 간다</strong>({@code findPlannableInWave}·{@code recordPlanResult}) — 최적화기의 입력은 순수
 * 값이고 결과 반영은 집합이라 영속성 컨텍스트를 지날 이유가 없다(ADR-029, §7.1).
 *
 * <p>이것이 성능 문제이기 전에 <em>의미</em> 문제였다는 것을 측정이 보여 줬다: 후보 5,000개를
 * 관리 엔티티로 올려 두면 그 뒤의 모든 네이티브 질의가 auto-flush 로 전수 더티 체크를 하고,
 * 같은 5,000행 INSERT 가 <strong>635 ms → 13,877 ms</strong> 가 된다
 * ({@code docs/benchmarks/phase4-plan-roundtrip-breakdown.md}).
 */
public class JpaDispatchCandidateRepository implements DispatchCandidateRepository {

    private static final String INSERT_SQL = """
            INSERT INTO dispatch_candidates (
                order_id, wave_id, camp_id, zone_id, lat, lng, geohash7,
                weight_g, volume_cm3, requires_cold, hazmat,
                promised_start, promised_end, service_seconds, promise_revised, priority,
                status, version, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
            ON CONFLICT (order_id) DO NOTHING
            """;

    /**
     * 술어를 <strong>리터럴로</strong> 적는다. 바인드 파라미터로 넣으면 플래너가 일반 계획에서
     * 술어를 증명하지 못해 {@code ix_cand_wave (wave_id, status)} 의 뒤 컬럼을 못 쓴다
     * (CLAUDE.md 코딩 컨벤션). 이 계획이 인덱스를 타는지는 {@code DispatchPersistenceIT} 가
     * <strong>ANALYZE 후에</strong> 확인한다 — 통계가 없으면 플래너는 짐작한다.
     */
    private static final String FIND_PLANNABLE_SQL = """
            SELECT order_id, wave_id, camp_id, zone_id, lat, lng, weight_g, volume_cm3,
                   requires_cold, hazmat, promised_start, promised_end, service_seconds,
                   promise_revised, priority, status, created_at, updated_at, version
              FROM dispatch_candidates
             WHERE wave_id = ? AND status = 'PENDING'
             ORDER BY order_id
            """;

    /**
     * 계획 결과를 집합으로 반영한다 (ADR-029).
     *
     * <p>{@code AND status = 'PENDING'} 이 {@code DispatchCandidate.recordPlanResult} 의
     * 축 규칙을 옮겨 적은 것이다 — {@code PLANNED}·{@code UNASSIGNED} 는 진행 축 1 이라
     * {@code PENDING}(0) 만 통과한다. 그래서 이미 {@code CANCELLED} 인 후보는 뒤집히지 않는다
     * (ADR-026 — 취소된 후보의 행은 남고 상태도 유지된다).
     */
    private static final String RECORD_RESULT_SQL = """
            UPDATE dispatch_candidates
               SET status = ?, updated_at = ?, version = version + 1
             WHERE order_id = ANY (?) AND status = 'PENDING'
            """;

    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;

    /**
     * @param entityManager 공유 EntityManager 프록시 (단건 경로)
     * @param jdbc          같은 트랜잭션에 참여하는 JDBC 템플릿 (계획 경로, ADR-029)
     */
    public JpaDispatchCandidateRepository(EntityManager entityManager, JdbcTemplate jdbc) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
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
                .setParameter(15, candidate.promiseRevised())
                .setParameter(16, (short) candidate.priority())
                .setParameter(17, candidate.status().name())
                .setParameter(18, candidate.createdAt())
                .setParameter(19, candidate.updatedAt())
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
        Objects.requireNonNull(waveId, "waveId");
        return List.copyOf(jdbc.query(FIND_PLANNABLE_SQL,
                (rs, rowNum) -> DispatchCandidate.rehydrate(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class),
                        GeoPoint.of(rs.getBigDecimal(5).doubleValue(),
                                rs.getBigDecimal(6).doubleValue()),
                        rs.getInt(7), rs.getInt(8), rs.getBoolean(9), rs.getBoolean(10),
                        new TimeWindow(rs.getObject(11, java.time.OffsetDateTime.class).toInstant(),
                                rs.getObject(12, java.time.OffsetDateTime.class).toInstant()),
                        rs.getInt(13), rs.getBoolean(14), rs.getShort(15),
                        CandidateStatus.valueOf(rs.getString(16)),
                        rs.getObject(17, java.time.OffsetDateTime.class).toInstant(),
                        rs.getObject(18, java.time.OffsetDateTime.class).toInstant(),
                        rs.getLong(19)),
                waveId));
    }

    @Override
    public int recordPlanResult(Collection<UUID> orderIds, CandidateStatus target, Instant at) {
        Objects.requireNonNull(orderIds, "orderIds");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(at, "at");
        if (target != CandidateStatus.PLANNED && target != CandidateStatus.UNASSIGNED) {
            throw ValidationException.field("target", target, "계획 결과는 PLANNED 또는 UNASSIGNED 입니다");
        }
        if (orderIds.isEmpty()) {
            return 0;
        }
        return jdbc.execute((java.sql.Connection connection) -> {
            try (java.sql.PreparedStatement statement =
                    connection.prepareStatement(RECORD_RESULT_SQL)) {
                statement.setString(1, target.name());
                statement.setObject(2, at.atOffset(java.time.ZoneOffset.UTC));
                statement.setArray(3,
                        connection.createArrayOf("uuid", orderIds.toArray(UUID[]::new)));
                return statement.executeUpdate();
            }
        });
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
