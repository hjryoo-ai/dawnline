package com.dawnline.order.adapter.out.persistence;

import com.dawnline.common.error.NotFoundException;
import com.dawnline.order.application.port.out.OrderRepository;
import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@link OrderRepository} 의 JPA 구현 (DESIGN.md §5.1).
 *
 * <p>{@code libs/messaging} 의 리포지토리들과 달리 여기서는 JPQL 을 쓴다. 저쪽은
 * {@code FOR UPDATE SKIP LOCKED} 가 실제로 생성되는지가 정확성의 핵심이라 네이티브 SQL 이어야
 * 했지만, 이 조회들은 그런 종류의 요구가 없고 엔티티 그래프(품목 element collection)를 그대로
 * 받는 편이 낫다.
 */
public class JpaOrderRepository implements OrderRepository {

    /**
     * 커서 페이지네이션의 고정 부분 (§5.1 {@code GET /api/v1/orders}).
     *
     * <p>{@code (placed_at DESC, id DESC)} 로 정렬하고 커서도 그 두 값으로 받는다. {@code OFFSET}
     * 을 쓰지 않는 이유는 두 가지다. 깊은 페이지에서 비용이 선형으로 늘고, 조회 사이에 새 주문이
     * 들어오면 페이지 경계가 밀려 같은 주문이 두 번 나오거나 건너뛴다.
     *
     * <p>{@code ix_orders_customer_placed (customer_id, placed_at DESC)} 를 탄다.
     */
    private static final String FIND_BY_CUSTOMER = "SELECT o FROM OrderEntity o WHERE o.customerId = :customerId";

    private static final String ORDER_BY = " ORDER BY o.placedAt DESC, o.id DESC";

    private final EntityManager entityManager;

    /**
     * @param entityManager 공유 EntityManager 프록시
     */
    public JpaOrderRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    }

    @Override
    public void save(Order order) {
        Objects.requireNonNull(order, "order");
        entityManager.persist(OrderEntity.from(order));
    }

    @Override
    public void update(Order order) {
        Objects.requireNonNull(order, "order");
        // find 로 관리 인스턴스를 가져와 고친다. merge 로 새 인스턴스를 밀어 넣으면 그 인스턴스의
        // version 이 기준이 되는데 도메인은 버전을 올리지 않으므로 읽은 시점 값 그대로다 —
        // 그 사이 다른 트랜잭션이 바꿔도 충돌로 잡히지 않는다 (OrderEntity#applyStateOf 참고).
        OrderEntity entity = entityManager.find(OrderEntity.class, order.id());
        if (entity == null) {
            throw NotFoundException.of("Order", order.id());
        }
        entity.applyStateOf(order);
    }

    @Override
    public Optional<Order> findById(UUID id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(entityManager.find(OrderEntity.class, id)).map(OrderEntity::toDomain);
    }

    @Override
    public List<Order> findByCustomer(UUID customerId, @Nullable OrderStatus status,
            @Nullable Instant from, @Nullable Instant to,
            @Nullable Instant cursorPlacedAt, @Nullable UUID cursorId, int limit) {
        Objects.requireNonNull(customerId, "customerId");
        if (limit < 1) {
            throw new IllegalArgumentException("limit 은 1 이상이어야 합니다: " + limit);
        }
        if (cursorPlacedAt != null && cursorId == null) {
            // 같은 밀리초의 주문들 사이에서 커서가 멈추지 못해 건너뛰거나 반복된다.
            throw new IllegalArgumentException("cursorPlacedAt 을 주면 cursorId 도 함께 주어야 합니다");
        }

        // 조건을 동적으로 붙인다. `(:param IS NULL OR ...)` 한 방에 쓰는 방식은 두 가지로 나쁘다.
        //  1) PostgreSQL 이 홀로 선 `? IS NULL` 의 타입을 추론하지 못해 실행 자체가 실패한다
        //     ("could not determine data type of parameter"). 실물 DB 없이는 안 드러나는 부류다.
        //  2) 그 술어는 플래너가 인덱스로 좁히기 어렵게 만든다 — 값이 들어오든 안 들어오든 같은 계획이다.
        StringBuilder jpql = new StringBuilder(FIND_BY_CUSTOMER);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("customerId", customerId);
        if (status != null) {
            jpql.append(" AND o.status = :status");
            parameters.put("status", status);
        }
        if (from != null) {
            jpql.append(" AND o.placedAt >= :from");
            parameters.put("from", from);
        }
        if (to != null) {
            jpql.append(" AND o.placedAt < :to");
            parameters.put("to", to);
        }
        if (cursorPlacedAt != null) {
            jpql.append(" AND (o.placedAt < :cursorPlacedAt"
                    + " OR (o.placedAt = :cursorPlacedAt AND o.id < :cursorId))");
            parameters.put("cursorPlacedAt", cursorPlacedAt);
            parameters.put("cursorId", cursorId);
        }
        jpql.append(ORDER_BY);

        TypedQuery<OrderEntity> query = entityManager.createQuery(jpql.toString(), OrderEntity.class)
                .setMaxResults(limit);
        parameters.forEach(query::setParameter);

        List<Order> orders = new ArrayList<>(limit);
        for (OrderEntity entity : query.getResultList()) {
            orders.add(entity.toDomain());
        }
        return List.copyOf(orders);
    }
}
