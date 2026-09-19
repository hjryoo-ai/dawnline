package com.dawnline.tracking.adapter.out.persistence;

import com.dawnline.tracking.application.port.out.ShipmentRepository;
import com.dawnline.tracking.domain.Shipment;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code shipments} 어댑터 (DESIGN.md §5.4).
 *
 * <p>{@code @Version} 낙관적 락이 붙어 있는 애그리거트라 영속성 컨텍스트를 그대로 쓴다. 같은
 * 배송을 개정 소비(라우트 키로 직렬화된다)와 기사 스캔(HTTP, 아무 스레드)이 동시에 건드릴 수
 * 있고, 그때 나중 쓰기가 앞 쓰기를 덮으면 「도착했는데 다시 예정으로 돌아간」 행이 남는다.
 *
 * <p>집합 경로(dispatch 의 계획 반영, ADR-029)와 달리 여기서 다루는 행 수는 한 라우트의 stop
 * 수(수십~백)이고 전부 상태 머신을 지난다. 벌크로 내려갈 이유가 없다.
 */
public class JpaShipmentRepository implements ShipmentRepository {

    private static final String FIND_ALL_JPQL =
            "SELECT s FROM ShipmentEntity s WHERE s.orderId IN :orderIds";

    private final EntityManager entityManager;

    /**
     * @param entityManager 공유 EntityManager 프록시
     */
    public JpaShipmentRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    }

    @Override
    public List<Shipment> findAll(Collection<UUID> orderIds) {
        Objects.requireNonNull(orderIds, "orderIds");
        if (orderIds.isEmpty()) {
            // 빈 IN 절은 문법 오류다. 그리고 "아무것도 찾지 않는다" 는 질의를 보낼 이유가 없다.
            return List.of();
        }
        return entityManager.createQuery(FIND_ALL_JPQL, ShipmentEntity.class)
                .setParameter("orderIds", orderIds)
                .getResultList().stream()
                .map(ShipmentEntity::toDomain)
                .toList();
    }

    @Override
    public void insert(Shipment shipment) {
        Objects.requireNonNull(shipment, "shipment");
        entityManager.persist(ShipmentEntity.from(shipment));
    }

    @Override
    public void update(Shipment shipment) {
        Objects.requireNonNull(shipment, "shipment");
        ShipmentEntity entity = entityManager.find(ShipmentEntity.class, shipment.orderId());
        if (entity == null) {
            // 부르는 쪽이 방금 읽은 행이다. 없다면 같은 트랜잭션 밖에서 지워졌다는 뜻이고,
            // 그런 삭제 경로는 이 서비스에 없다 — 조용히 넣어 버리면 그 사실이 묻힌다.
            throw new IllegalStateException("없는 배송을 갱신할 수 없습니다: " + shipment.orderId());
        }
        entity.apply(shipment);
    }
}
