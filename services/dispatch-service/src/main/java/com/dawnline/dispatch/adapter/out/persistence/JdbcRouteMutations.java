package com.dawnline.dispatch.adapter.out.persistence;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.port.out.RouteMutations;
import com.dawnline.dispatch.domain.optimizer.OrderId;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import com.dawnline.dispatch.domain.optimizer.PlannedStop;
import com.dawnline.dispatch.domain.optimizer.Stop;
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

/**
 * 라우트 조작 어댑터 (DESIGN.md §5.3 운영자 재배정).
 *
 * <p>화물·약속창을 {@code dispatch_candidates} 에서 가져오는 이유: 계획의 <em>근거</em>는 후보이고
 * {@code route_stops} 는 그 <em>결과</em>다. 룰을 다시 돌리려면 근거가 필요하다.
 */
public class JdbcRouteMutations implements RouteMutations {

    /**
     * 순번을 다시 매기는 동안 기존 순번을 피신시킬 거리. max-stops 상한(120, §6.3)보다 크고
     * {@code SMALLINT} 안이면 된다.
     */
    private static final int SEQ_PARK_OFFSET = 1000;

    private final EntityManager entityManager;

    /**
     * @param entityManager 공유 EntityManager 프록시
     */
    public JdbcRouteMutations(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<RouteHeader> findHeader(UUID routeId) {
        List<Object[]> rows = entityManager
                .createNativeQuery("SELECT id, plan_id, vehicle_id FROM routes WHERE id = ?")
                .setParameter(1, routeId).getResultList();
        return rows.isEmpty() ? Optional.empty()
                : Optional.of(new RouteHeader((UUID) rows.getFirst()[0], (UUID) rows.getFirst()[1],
                        (UUID) rows.getFirst()[2]));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Stop> loadStops(UUID routeId) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT s.id, s.seq, s.lat, s.lng, s.service_s, o.order_id,
                       c.weight_g, c.volume_cm3, c.requires_cold, c.hazmat,
                       c.promised_start, c.promised_end, c.priority
                  FROM route_stops s
                  JOIN route_stop_orders o ON o.stop_id = s.id
                  JOIN dispatch_candidates c ON c.order_id = o.order_id
                 WHERE s.route_id = ?
                 ORDER BY s.seq, o.order_id
                """).setParameter(1, routeId).getResultList();

        Map<UUID, Builder> byStop = new LinkedHashMap<>();
        for (Object[] row : rows) {
            Builder builder = byStop.computeIfAbsent((UUID) row[0], id -> new Builder(
                    GeoPoint.of(((BigDecimal) row[2]).doubleValue(),
                            ((BigDecimal) row[3]).doubleValue()),
                    ((Number) row[4]).intValue(),
                    new TimeWindow((Instant) row[10], (Instant) row[11])));
            builder.add(OrderId.of((UUID) row[5]),
                    new Parcel(((Number) row[6]).intValue(), ((Number) row[7]).intValue(),
                            (Boolean) row[8], (Boolean) row[9]),
                    ((Number) row[12]).intValue());
        }
        return byStop.values().stream().map(Builder::build).toList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<UUID> findStopOf(UUID routeId, UUID orderId) {
        List<UUID> rows = entityManager.createNativeQuery("""
                SELECT s.id FROM route_stops s
                  JOIN route_stop_orders o ON o.stop_id = s.id
                 WHERE s.route_id = ? AND o.order_id = ?
                """).setParameter(1, routeId).setParameter(2, orderId).getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void moveOrder(UUID fromStopId, UUID orderId, UUID targetRouteId) {
        List<Object[]> candidate = entityManager.createNativeQuery("""
                SELECT lat, lng, service_seconds FROM dispatch_candidates WHERE order_id = ?
                """).setParameter(1, orderId).getResultList();
        if (candidate.isEmpty()) {
            throw new IllegalStateException("후보가 없는 주문은 옮길 수 없습니다: " + orderId);
        }
        BigDecimal lat = (BigDecimal) candidate.getFirst()[0];
        BigDecimal lng = (BigDecimal) candidate.getFirst()[1];

        // 목적지에 같은 지점의 stop 이 있으면 거기 붙인다 — 없는데 새로 만들면 같은 건물을
        // 두 번 방문하는 라우트가 된다.
        List<UUID> existing = entityManager.createNativeQuery("""
                SELECT id FROM route_stops WHERE route_id = ? AND lat = ? AND lng = ? LIMIT 1
                """).setParameter(1, targetRouteId).setParameter(2, lat).setParameter(3, lng)
                .getResultList();

        UUID targetStopId = existing.isEmpty()
                ? createStop(targetRouteId, lat, lng, ((Number) candidate.getFirst()[2]).intValue())
                : existing.getFirst();

        entityManager.createNativeQuery(
                "UPDATE route_stop_orders SET stop_id = ? WHERE stop_id = ? AND order_id = ?")
                .setParameter(1, targetStopId).setParameter(2, fromStopId)
                .setParameter(3, orderId).executeUpdate();

        // 비워진 stop 은 지운다. 남겨 두면 seq 재부여가 유령 지점을 셈에 넣는다.
        entityManager.createNativeQuery("""
                DELETE FROM route_stops s
                 WHERE s.id = ? AND NOT EXISTS (
                       SELECT 1 FROM route_stop_orders o WHERE o.stop_id = s.id)
                """).setParameter(1, fromStopId).executeUpdate();
    }

    private UUID createStop(UUID routeId, BigDecimal lat, BigDecimal lng, int serviceSeconds) {
        UUID stopId = Ids.newId();
        Number maxSeq = (Number) entityManager.createNativeQuery(
                        "SELECT COALESCE(max(seq), 0) FROM route_stops WHERE route_id = ?")
                .setParameter(1, routeId).getSingleResult();
        entityManager.createNativeQuery("""
                INSERT INTO route_stops (id, route_id, seq, lat, lng, planned_arrival,
                                         planned_departure, service_s, status)
                VALUES (?, ?, ?, ?, ?, now(), now(), ?, 'PLANNED')
                """)
                .setParameter(1, stopId).setParameter(2, routeId)
                .setParameter(3, (short) (maxSeq.intValue() + 1))
                .setParameter(4, lat).setParameter(5, lng).setParameter(6, serviceSeconds)
                .executeUpdate();
        return stopId;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void rewrite(UUID routeId, PlannedRoute route) {
        // 순번은 UNIQUE (route_id, seq) 다. 한 번에 옮기면 중간에 충돌하므로 잠시 피신시킨다.
        // 음수로 보내면 안 된다 — 같은 컬럼에 CHECK (seq >= 1) 이 걸려 있다. 두 제약을 동시에
        // 만족하는 자리는 "현재 순번보다 크고 SMALLINT 안" 이고, max-stops 가 120 이므로(§6.3)
        // 1000 을 더하면 겹치지 않는다. DispatchAdminIT 가 이 자리에서 음수 버전을 잡았다.
        entityManager.createNativeQuery(
                        "UPDATE route_stops SET seq = seq + " + SEQ_PARK_OFFSET
                                + " WHERE route_id = ?")
                .setParameter(1, routeId).executeUpdate();

        List<Object[]> stopIds = entityManager.createNativeQuery("""
                SELECT s.id, s.lat, s.lng FROM route_stops s WHERE s.route_id = ?
                """).setParameter(1, routeId).getResultList();
        Map<String, UUID> byPoint = new LinkedHashMap<>();
        for (Object[] row : stopIds) {
            byPoint.put(key((BigDecimal) row[1], (BigDecimal) row[2]), (UUID) row[0]);
        }

        for (PlannedStop planned : route.stops()) {
            UUID stopId = byPoint.get(key(planned.stop().point()));
            if (stopId == null) {
                throw new IllegalStateException("다시 쓸 stop 을 찾지 못했습니다: " + planned.seq());
            }
            entityManager.createNativeQuery("""
                    UPDATE route_stops SET seq = ?, planned_arrival = ?, planned_departure = ?,
                                           service_s = ?
                     WHERE id = ?
                    """)
                    .setParameter(1, (short) planned.seq())
                    .setParameter(2, planned.arrival())
                    .setParameter(3, planned.departure())
                    .setParameter(4, planned.stop().serviceSeconds())
                    .setParameter(5, stopId).executeUpdate();
        }

        entityManager.createNativeQuery("""
                UPDATE routes SET stop_count = ?, distance_m = ?, duration_s = ?, cost_krw = ?
                 WHERE id = ?
                """)
                .setParameter(1, route.stops().size())
                .setParameter(2, route.distanceM())
                .setParameter(3, route.durationS())
                .setParameter(4, route.cost().krw())
                .setParameter(5, routeId).executeUpdate();
    }

    @Override
    public void clear(UUID routeId) {
        entityManager.createNativeQuery("""
                DELETE FROM route_stop_orders WHERE stop_id IN (
                       SELECT id FROM route_stops WHERE route_id = ?)
                """).setParameter(1, routeId).executeUpdate();
        entityManager.createNativeQuery("DELETE FROM route_stops WHERE route_id = ?")
                .setParameter(1, routeId).executeUpdate();
        entityManager.createNativeQuery("""
                UPDATE routes SET stop_count = 0, distance_m = 0, duration_s = 0, cost_krw = 0
                 WHERE id = ?
                """).setParameter(1, routeId).executeUpdate();
    }

    @Override
    public int bumpRevision(UUID routeId) {
        entityManager.createNativeQuery("UPDATE routes SET revision = revision + 1 WHERE id = ?")
                .setParameter(1, routeId).executeUpdate();
        return ((Number) entityManager
                .createNativeQuery("SELECT revision FROM routes WHERE id = ?")
                .setParameter(1, routeId).getSingleResult()).intValue();
    }

    private static String key(GeoPoint point) {
        return key(BigDecimal.valueOf(point.lat()).setScale(6, java.math.RoundingMode.HALF_UP),
                BigDecimal.valueOf(point.lng()).setScale(6, java.math.RoundingMode.HALF_UP));
    }

    private static String key(BigDecimal lat, BigDecimal lng) {
        return lat.setScale(6, java.math.RoundingMode.HALF_UP).toPlainString() + ','
                + lng.setScale(6, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** stop 하나를 여러 행에서 모은다. */
    private static final class Builder {

        private final GeoPoint point;
        private final int serviceSeconds;
        private final TimeWindow promised;
        private final List<OrderId> orderIds = new ArrayList<>();
        private Parcel parcel = Parcel.EMPTY;
        private int priority;

        private Builder(GeoPoint point, int serviceSeconds, TimeWindow promised) {
            this.point = point;
            this.serviceSeconds = serviceSeconds;
            this.promised = promised;
        }

        private void add(OrderId orderId, Parcel added, int orderPriority) {
            orderIds.add(orderId);
            parcel = parcel.plus(added);
            priority = Math.max(priority, orderPriority);
        }

        private Stop build() {
            return new Stop(point, orderIds, parcel, promised, serviceSeconds, priority);
        }
    }
}
