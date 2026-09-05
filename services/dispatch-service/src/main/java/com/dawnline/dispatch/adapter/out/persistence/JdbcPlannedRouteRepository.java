package com.dawnline.dispatch.adapter.out.persistence;

import com.dawnline.common.Ids;
import com.dawnline.dispatch.application.port.out.PlannedRouteRepository;
import com.dawnline.dispatch.domain.optimizer.Explanation;
import com.dawnline.dispatch.domain.optimizer.OrderId;
import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import com.dawnline.dispatch.domain.optimizer.PlannedStop;
import com.dawnline.dispatch.domain.optimizer.VehicleId;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * 라우트·stop·설명 저장 (DESIGN.md §5.3).
 *
 * <p>JPA 엔티티가 아니라 네이티브 SQL 이다. 이 세 표는 <strong>쓰기만 하고 애그리거트로 읽지
 * 않는다</strong> — 읽는 쪽은 ops REST(5c)의 조회이고 그건 DTO 로 바로 뜬다. 엔티티를 만들면
 * 영속성 컨텍스트가 5,000 stop 을 들고 있게 되는데, 그것을 얻는 대가로 아무것도 얻지 못한다.
 */
public class JdbcPlannedRouteRepository implements PlannedRouteRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EntityManager entityManager;

    /**
     * @param entityManager 공유 EntityManager 프록시
     */
    public JdbcPlannedRouteRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    }

    @Override
    public List<UUID> saveRoutes(UUID planId, List<PlannedRoute> routes) {
        Objects.requireNonNull(planId, "planId");
        List<UUID> routeIds = new ArrayList<>(routes.size());
        for (int i = 0; i < routes.size(); i++) {
            PlannedRoute route = routes.get(i);
            UUID routeId = Ids.newId();
            routeIds.add(routeId);
            entityManager.createNativeQuery("""
                    INSERT INTO routes (id, plan_id, vehicle_id, seq_no, status, revision,
                                        stop_count, distance_m, duration_s, cost_krw, version)
                    VALUES (?, ?, ?, ?, 'PLANNED', 1, ?, ?, ?, ?, 0)
                    """)
                    .setParameter(1, routeId)
                    .setParameter(2, planId)
                    .setParameter(3, route.vehicle().value())
                    .setParameter(4, (short) (i + 1))
                    .setParameter(5, route.stops().size())
                    .setParameter(6, route.distanceM())
                    .setParameter(7, route.durationS())
                    .setParameter(8, route.cost().krw())
                    .executeUpdate();
            saveStops(routeId, route);
        }
        return List.copyOf(routeIds);
    }

    private void saveStops(UUID routeId, PlannedRoute route) {
        for (PlannedStop planned : route.stops()) {
            UUID stopId = Ids.newId();
            entityManager.createNativeQuery("""
                    INSERT INTO route_stops (id, route_id, seq, lat, lng, planned_arrival,
                                             planned_departure, service_s, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PLANNED')
                    """)
                    .setParameter(1, stopId)
                    .setParameter(2, routeId)
                    .setParameter(3, (short) planned.seq())
                    .setParameter(4, planned.stop().point().lat())
                    .setParameter(5, planned.stop().point().lng())
                    .setParameter(6, planned.arrival())
                    .setParameter(7, planned.departure())
                    .setParameter(8, planned.stop().serviceSeconds())
                    .executeUpdate();
            for (OrderId orderId : planned.stop().orderIds()) {
                entityManager.createNativeQuery(
                        "INSERT INTO route_stop_orders (stop_id, order_id) VALUES (?, ?)")
                        .setParameter(1, stopId)
                        .setParameter(2, orderId.value())
                        .executeUpdate();
            }
        }
    }

    @Override
    public void saveExplanations(UUID planId, List<Explanation> explanations,
            Map<VehicleId, UUID> routeIds) {

        Objects.requireNonNull(planId, "planId");
        for (Explanation explanation : explanations) {
            entityManager.createNativeQuery("""
                    INSERT INTO plan_explanations (id, plan_id, order_id, vehicle_id, rule_name,
                                                   outcome, detail)
                    VALUES (?, ?, ?, ?, ?, ?, cast(? as jsonb))
                    """)
                    .setParameter(1, Ids.newId())
                    .setParameter(2, planId)
                    .setParameter(3, explanation.orderId().value())
                    .setParameter(4, explanation.vehicle() == null
                            ? null : explanation.vehicle().value())
                    .setParameter(5, explanation.ruleName())
                    .setParameter(6, explanation.outcome().name())
                    .setParameter(7, JSON.writeValueAsString(explanation.detail()))
                    .executeUpdate();
        }
    }
}
