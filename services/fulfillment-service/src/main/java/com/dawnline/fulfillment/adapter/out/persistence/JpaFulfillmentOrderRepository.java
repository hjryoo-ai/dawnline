package com.dawnline.fulfillment.adapter.out.persistence;

import com.dawnline.common.error.NotFoundException;
import com.dawnline.fulfillment.application.port.out.FulfillmentOrderRepository;
import com.dawnline.fulfillment.domain.FulfillmentOrder;
import com.dawnline.fulfillment.domain.FulfillmentOrderStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link FulfillmentOrderRepository} 의 JPA 구현 (ADR-022).
 */
public class JpaFulfillmentOrderRepository implements FulfillmentOrderRepository {

    /**
     * 없으면 만든다.
     *
     * <p>PK 가 {@code order_id} 단독이라 충돌 대상도 그 한 컬럼이다. 두 리스너
     * ({@code order.placed}·{@code order.cancelled})가 같은 주문으로 동시에 들어오면 여기서
     * 한쪽이 대기하고, 진 쪽은 0을 받아 재조회 후 상태 머신을 적용한다 (ADR-022 결정 4).
     */
    private static final String INSERT_SQL = """
            INSERT INTO fulfillment_orders (
                order_id, status, wave_id, camp_id, fc_id, zone_id, cutoff_at,
                promised_start, promised_end, promise_revised, unserviceable_reason,
                fc_fallback_reason, placed_event_id, cancelled_at, version, created_at, updated_at)
            VALUES (:orderId, :status, :waveId, :campId, :fcId, :zoneId, :cutoffAt,
                :promisedStart, :promisedEnd, :promiseRevised, :unserviceableReason,
                :fcFallbackReason, :placedEventId, :cancelledAt, 0, :createdAt, :updatedAt)
            ON CONFLICT (order_id) DO NOTHING
            """;

    /**
     * 보존 만료 삭제 (ADR-023 결정 1 — 30일, {@code updated_at} 기준).
     *
     * <p>나이만이 아니라 <strong>종결 상태인지</strong>도 본다. {@code PLANNED} 행은 소속 웨이브가
     * 계획을 끝냈을 때만 종결이다 — 아직 열려 있거나 마감 중인 웨이브의 주문은 진행 중이고, 30일
     * 넘게 열려 있는 웨이브는 그 자체가 사고다. 사고 상황에서 데이터를 먼저 지우는 정리가 최악이다.
     *
     * <p>{@code ORDER BY updated_at} 이 {@code ix_fulfillment_orders_cleanup} 을 타면서 오래된
     * 행부터 집게 한다 — 반복 호출이 매번 앞으로 나아가는 것을 보장한다. 인덱스가 없으면 배치마다
     * 4.65M 행을 다시 훑어 하루치 정리가 0.24초 대신 68초가 된다
     * (docs/benchmarks/phase2-fulfillment-orders-indexes.md §1).
     */
    private static final String DELETE_EXPIRED_SQL = """
            DELETE FROM fulfillment_orders
             WHERE ctid IN (
                   SELECT fo.ctid FROM fulfillment_orders fo
                    WHERE fo.updated_at < :threshold
                      AND (fo.status IN ('CANCELLED', 'UNSERVICEABLE')
                           OR EXISTS (SELECT 1 FROM waves w
                                       WHERE w.id = fo.wave_id
                                         AND w.status IN ('PLANNED', 'PLAN_FAILED')))
                    ORDER BY fo.updated_at
                    LIMIT :limit)
            """;

    private final EntityManager entityManager;

    /**
     * @param entityManager 공유 EntityManager 프록시
     */
    public JpaFulfillmentOrderRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    }

    @Override
    public boolean insertIfAbsent(FulfillmentOrder order) {
        Objects.requireNonNull(order, "order");
        int inserted = entityManager.createNativeQuery(INSERT_SQL)
                .setParameter("orderId", order.orderId())
                .setParameter("status", order.status().name())
                .setParameter("waveId", order.waveId().orElse(null))
                .setParameter("campId", order.campId().orElse(null))
                .setParameter("fcId", order.fcId().orElse(null))
                .setParameter("zoneId", order.zoneId().orElse(null))
                .setParameter("cutoffAt", order.cutoffAt().orElse(null))
                .setParameter("promisedStart", order.promisedWindow().map(w -> w.start()).orElse(null))
                .setParameter("promisedEnd", order.promisedWindow().map(w -> w.end()).orElse(null))
                .setParameter("promiseRevised", order.promiseRevised())
                .setParameter("unserviceableReason", order.unserviceableReason().map(Enum::name).orElse(null))
                .setParameter("fcFallbackReason", order.fcFallbackReason().map(Enum::name).orElse(null))
                .setParameter("placedEventId", order.placedEventId().orElse(null))
                .setParameter("cancelledAt", order.cancelledAt().orElse(null))
                .setParameter("createdAt", order.createdAt())
                .setParameter("updatedAt", order.updatedAt())
                .executeUpdate();
        return inserted == 1;
    }

    @Override
    public Optional<FulfillmentOrder> findById(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        return Optional.ofNullable(entityManager.find(FulfillmentOrderEntity.class, orderId))
                .map(FulfillmentOrderEntity::toDomain);
    }

    @Override
    public List<FulfillmentOrder> findPlannedInWave(UUID waveId) {
        Objects.requireNonNull(waveId, "waveId");
        List<FulfillmentOrderEntity> rows = entityManager.createQuery("""
                        SELECT o FROM FulfillmentOrderEntity o
                         WHERE o.waveId = :waveId AND o.status = :status
                         ORDER BY o.orderId""", FulfillmentOrderEntity.class)
                .setParameter("waveId", waveId)
                .setParameter("status", FulfillmentOrderStatus.PLANNED)
                .getResultList();
        List<FulfillmentOrder> orders = new ArrayList<>(rows.size());
        for (FulfillmentOrderEntity row : rows) {
            orders.add(row.toDomain());
        }
        return List.copyOf(orders);
    }

    @Override
    public int countPlannedInWave(UUID waveId) {
        Objects.requireNonNull(waveId, "waveId");
        Number count = (Number) entityManager.createNativeQuery("""
            SELECT count(*) FROM fulfillment_orders WHERE wave_id = :waveId AND status = 'PLANNED'
            """)
                .setParameter("waveId", waveId)
                .getSingleResult();
        return count.intValue();
    }

    @Override
    public void update(FulfillmentOrder order) {
        Objects.requireNonNull(order, "order");
        FulfillmentOrderEntity entity = entityManager.find(FulfillmentOrderEntity.class, order.orderId());
        if (entity == null) {
            throw NotFoundException.of("FulfillmentOrder", order.orderId());
        }
        entity.applyStateOf(order);
    }

    @Override
    public int deleteSettledUpdatedBefore(Instant updatedBefore, int limit) {
        Objects.requireNonNull(updatedBefore, "updatedBefore");
        if (limit < 1) {
            throw new IllegalArgumentException("limit 은 1 이상이어야 합니다: " + limit);
        }
        return entityManager.createNativeQuery(DELETE_EXPIRED_SQL)
                .setParameter("threshold", updatedBefore)
                .setParameter("limit", limit)
                .executeUpdate();
    }
}
