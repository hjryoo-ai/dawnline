package com.dawnline.dispatch.adapter.out.persistence;

import com.dawnline.dispatch.application.port.in.PlanView;
import com.dawnline.dispatch.application.port.in.RouteView;
import com.dawnline.dispatch.application.port.out.PlanQueries;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 조회 전용 어댑터 (DESIGN.md §5.3). */
public class JdbcPlanQueries implements PlanQueries {

    private final EntityManager entityManager;

    /**
     * @param entityManager 공유 EntityManager 프록시
     */
    public JdbcPlanQueries(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    }

    @Override
    public Optional<PlanView> findPlan(UUID planId) {
        return plan("SELECT id, wave_id, camp_id, status, strategy, mode, rule_version, started_at,"
                + " finished_at, total_cost_krw, assigned_count, unassigned_count, plan_duration_ms,"
                + " failure_reason FROM route_plans WHERE id = ?", planId);
    }

    @Override
    public Optional<PlanView> findPlanByWave(UUID waveId) {
        return plan("SELECT id, wave_id, camp_id, status, strategy, mode, rule_version, started_at,"
                + " finished_at, total_cost_krw, assigned_count, unassigned_count, plan_duration_ms,"
                + " failure_reason FROM route_plans WHERE wave_id = ?", waveId);
    }

    @SuppressWarnings("unchecked")
    private Optional<PlanView> plan(String sql, UUID key) {
        List<Object[]> rows = entityManager.createNativeQuery(sql).setParameter(1, key)
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.getFirst();
        UUID planId = (UUID) row[0];
        return Optional.of(new PlanView(planId, (UUID) row[1], (UUID) row[2], (String) row[3],
                (String) row[4], (String) row[5], intOrNull(row[6]), instantOrNull(row[7]),
                instantOrNull(row[8]), longOrNull(row[9]), intOrNull(row[10]), intOrNull(row[11]),
                intOrNull(row[12]), (String) row[13], routeSummaries(planId), explanations(planId)));
    }

    @SuppressWarnings("unchecked")
    private List<PlanView.RouteSummary> routeSummaries(UUID planId) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT id, vehicle_id, seq_no, revision, stop_count, distance_m, duration_s, cost_krw
                  FROM routes WHERE plan_id = ? ORDER BY seq_no
                """).setParameter(1, planId).getResultList();
        List<PlanView.RouteSummary> summaries = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            summaries.add(new PlanView.RouteSummary((UUID) row[0], (UUID) row[1],
                    ((Number) row[2]).intValue(), ((Number) row[3]).intValue(),
                    ((Number) row[4]).intValue(), ((Number) row[5]).intValue(),
                    ((Number) row[6]).intValue(), ((Number) row[7]).longValue()));
        }
        return List.copyOf(summaries);
    }

    @SuppressWarnings("unchecked")
    private List<PlanView.ExplanationView> explanations(UUID planId) {
        // ix_expl_plan_order (plan_id, order_id) 를 탄다 — 운영자의 "이 주문은 왜" 질의가
        // 이 인덱스의 존재 이유다 (§5.3).
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT order_id, outcome, rule_name, vehicle_id, detail::text
                  FROM plan_explanations WHERE plan_id = ? ORDER BY order_id
                """).setParameter(1, planId).getResultList();
        List<PlanView.ExplanationView> views = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            views.add(new PlanView.ExplanationView((UUID) row[0], (String) row[1], (String) row[2],
                    (UUID) row[3], (String) row[4]));
        }
        return List.copyOf(views);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<RouteView> findRoute(UUID routeId) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT r.id, r.plan_id, r.vehicle_id, r.driver_id, r.status, r.revision,
                       r.distance_m, r.duration_s, r.cost_krw
                  FROM routes r WHERE r.id = ?
                """).setParameter(1, routeId).getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.getFirst();
        return Optional.of(new RouteView((UUID) row[0], (UUID) row[1], (UUID) row[2], (UUID) row[3],
                (String) row[4], ((Number) row[5]).intValue(), ((Number) row[6]).intValue(),
                ((Number) row[7]).intValue(), ((Number) row[8]).longValue(), stops(routeId)));
    }

    @SuppressWarnings("unchecked")
    private List<RouteView.StopView> stops(UUID routeId) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT id, seq, lat, lng, planned_arrival, planned_departure, service_s, status
                  FROM route_stops WHERE route_id = ? ORDER BY seq
                """).setParameter(1, routeId).getResultList();

        Map<UUID, List<UUID>> orders = ordersByStop(routeId);
        List<RouteView.StopView> stops = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            UUID stopId = (UUID) row[0];
            stops.add(new RouteView.StopView(stopId, ((Number) row[1]).intValue(),
                    ((BigDecimal) row[2]).doubleValue(), ((BigDecimal) row[3]).doubleValue(),
                    (Instant) row[4], (Instant) row[5], ((Number) row[6]).intValue(),
                    (String) row[7], orders.getOrDefault(stopId, List.of())));
        }
        return List.copyOf(stops);
    }

    /** stop 마다 질의하면 N+1 이다 — 라우트 하나가 120 stop 까지 간다(§6.3 max-stops). */
    @SuppressWarnings("unchecked")
    private Map<UUID, List<UUID>> ordersByStop(UUID routeId) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT o.stop_id, o.order_id FROM route_stop_orders o
                  JOIN route_stops s ON s.id = o.stop_id
                 WHERE s.route_id = ? ORDER BY o.stop_id, o.order_id
                """).setParameter(1, routeId).getResultList();
        Map<UUID, List<UUID>> byStop = new LinkedHashMap<>();
        for (Object[] row : rows) {
            byStop.computeIfAbsent((UUID) row[0], key -> new ArrayList<>()).add((UUID) row[1]);
        }
        return byStop;
    }

    private static Integer intOrNull(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static Long longOrNull(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static Instant instantOrNull(Object value) {
        return (Instant) value;
    }
}
