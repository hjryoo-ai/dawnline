package com.dawnline.dispatch.adapter.out.persistence;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.dispatch.application.port.out.RouteMutations;
import com.dawnline.dispatch.application.port.out.RouteSnapshot;
import com.dawnline.dispatch.domain.DispatchErrorCode;
import com.dawnline.dispatch.domain.RouteStopStatus;
import com.dawnline.dispatch.domain.optimizer.OrderId;
import com.dawnline.dispatch.domain.optimizer.Parcel;
import com.dawnline.dispatch.domain.optimizer.PlannedRoute;
import com.dawnline.dispatch.domain.optimizer.PlannedStop;
import com.dawnline.dispatch.domain.optimizer.Stop;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Clock;
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
 * 라우트 조작 어댑터 (DESIGN.md §5.3 운영자 재배정).
 *
 * <p>화물·약속창을 {@code dispatch_candidates} 에서 가져오는 이유: 계획의 <em>근거</em>는 후보이고
 * {@code route_stops} 는 그 <em>결과</em>다. 룰을 다시 돌리려면 근거가 필요하다.
 *
 * <h2>후보가 없으면 실패한다 — 빼지 않는다</h2>
 * 후보는 보존 정리가 지운다(ADR-059 — 계획이 끝났을 때만, 계획 단위로). 그 조건이 이 클래스의 조인을 덮는 것은
 * <em>고른 결과</em>이지 강제가 아니라서, 후보를 읽는 네 자리는 후보가 없는 주문을 만나면
 * {@code candidates-expired}(409)로 실패한다. 내부 조인이던 때는 그 주문이 — 주문이 전부 그런 stop 이면 stop 이 —
 * 목록에서 <strong>조용히 빠졌고</strong>, 빠진 목록이 비면 재배정이 그 라우트를 비웠다.
 */
public class JdbcRouteMutations implements RouteMutations {

    private final EntityManager entityManager;
    private final Clock clock;

    /**
     * @param entityManager 공유 EntityManager 프록시
     * @param clock         새 stop 의 자리표시 시각 (불변규칙 12 — SQL 의 {@code now()} 가 아니다, ADR-066 결정 4)
     */
    public JdbcRouteMutations(EntityManager entityManager, Clock clock) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.clock = Objects.requireNonNull(clock, "clock");
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
    public List<PositionedStop> loadPositionedStops(UUID routeId) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT s.id, s.seq, s.lat, s.lng, s.service_s, o.order_id,
                       c.weight_g, c.volume_cm3, c.requires_cold, c.hazmat,
                       c.promised_start, c.promised_end, c.priority
                  FROM route_stops s
                  JOIN route_stop_orders o ON o.stop_id = s.id
                  LEFT JOIN dispatch_candidates c ON c.order_id = o.order_id
                 WHERE s.route_id = ? AND s.status <> 'CANCELLED'
                   AND (c.order_id IS NULL OR c.status <> 'CANCELLED')
                 ORDER BY s.seq, o.order_id
                """).setParameter(1, routeId).getResultList();

        Map<UUID, Builder> byStop = new LinkedHashMap<>();
        Map<UUID, Integer> seqOf = new LinkedHashMap<>();
        for (Object[] row : rows) {
            if (row[6] == null) {
                // 후보가 없다 — 빼면 이 stop 이 계산에서 사라지고 다시 쓰기가 그 자리를 지운다(ADR-059 결정 3).
                throw DispatchErrorCode.candidatesExpired(routeId, (UUID) row[5]);
            }
            Builder builder = byStop.computeIfAbsent((UUID) row[0], id -> new Builder(
                    GeoPoint.of(((BigDecimal) row[2]).doubleValue(),
                            ((BigDecimal) row[3]).doubleValue()),
                    ((Number) row[4]).intValue(),
                    new TimeWindow((Instant) row[10], (Instant) row[11])));
            seqOf.putIfAbsent((UUID) row[0], ((Number) row[1]).intValue());
            builder.add(OrderId.of((UUID) row[5]),
                    new Parcel(((Number) row[6]).intValue(), ((Number) row[7]).intValue(),
                            (Boolean) row[8], (Boolean) row[9]),
                    ((Number) row[12]).intValue());
        }
        return byStop.entrySet().stream()
                .map(entry -> new PositionedStop(seqOf.get(entry.getKey()),
                        entry.getValue().build()))
                .toList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<RouteHeader> routesOfPlan(UUID planId) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT id, plan_id, vehicle_id FROM routes WHERE plan_id = ? ORDER BY seq_no
                """).setParameter(1, planId).getResultList();
        return rows.stream()
                .map(row -> new RouteHeader((UUID) row[0], (UUID) row[1], (UUID) row[2]))
                .toList();
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
                SELECT lat, lng, service_seconds, promised_start, promised_end
                  FROM dispatch_candidates WHERE order_id = ?
                """).setParameter(1, orderId).getResultList();
        if (candidate.isEmpty()) {
            // 500 이던 자리다 — 원인은 이 서비스의 결함이 아니라 보존 정리다(ADR-059 결정 3).
            throw DispatchErrorCode.candidatesExpired(targetRouteId, orderId);
        }
        BigDecimal lat = (BigDecimal) candidate.getFirst()[0];
        BigDecimal lng = (BigDecimal) candidate.getFirst()[1];

        // 목적지에 같은 지점의 stop 이 있으면 거기 붙인다 — 없는데 새로 만들면 같은 건물을
        // 두 번 방문하는 라우트가 된다. 취소된 stop 에는 붙이지 않는다 — 기사가 건너뛰는
        // 지점에 살아 있는 주문을 얹으면 그 주문은 배송되지 않는다 (§6.10).
        List<UUID> existing = entityManager.createNativeQuery("""
                SELECT id FROM route_stops
                 WHERE route_id = ? AND lat = ? AND lng = ? AND status <> 'CANCELLED'
                 LIMIT 1
                """).setParameter(1, targetRouteId).setParameter(2, lat).setParameter(3, lng)
                .getResultList();

        UUID targetStopId = existing.isEmpty()
                ? createStop(targetRouteId, lat, lng, ((Number) candidate.getFirst()[2]).intValue(),
                        (Instant) candidate.getFirst()[3], (Instant) candidate.getFirst()[4])
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

    /**
     * 목적지에 새 stop 을 만든다.
     *
     * <p><strong>약속창을 함께 쓴다</strong> (2026-09-23, Phase 5-3). V6 이 그 컬럼을 더한 이유가
     * 「개정 발행이 {@code promisedWindow} 를 required 로 싣는다」였는데(§5.3), 이 INSERT 만 그
     * 두 칸을 비운 채 두고 있었다 — 그래서 재배정·재계획이 <em>새</em> stop 을 만든 라우트는
     * 그 다음 개정 발행에서 「약속창 없이 개정을 발행할 수 없습니다」로 터졌다.
     * 값은 통합 전 후보의 창이다: {@code StopMerger} 의 병합 키가 「같은 지점 + 같은 약속창」이라
     * (§6.5 1단계) stop 하나의 창은 그 위 주문들의 창과 같다.
     *
     * <p>드러난 경로는 §6.8 재계획이지만 결함은 §5.3 운영자 재배정에도 있었다 — 저쪽은 목적지에
     * 같은 지점의 stop 이 있는 경우만 IT 가 보고 있었다.
     */
    private UUID createStop(UUID routeId, BigDecimal lat, BigDecimal lng, int serviceSeconds,
            @Nullable Instant promisedStart, @Nullable Instant promisedEnd) {

        UUID stopId = Ids.newId();
        // 도착 · 출발은 자리표시자다 — 부르는 쪽(재배정 · 재계획)이 같은 트랜잭션에서 rewrite 로 덮는다. 그래도 DB 의 벽시계가
        // 아니라 주입 시계다: 다른 모든 시각 칸과 같은 시계여야 오프셋 아래에서도 한 행 안의 시각이 서로 어긋나지 않는다.
        Instant placeholder = clock.instant();
        Number maxSeq = (Number) entityManager.createNativeQuery(
                        "SELECT COALESCE(max(seq), 0) FROM route_stops WHERE route_id = ?")
                .setParameter(1, routeId).getSingleResult();
        entityManager.createNativeQuery("""
                INSERT INTO route_stops (id, route_id, seq, lat, lng, planned_arrival,
                                         planned_departure, service_s, status,
                                         promised_start, promised_end)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PLANNED', ?, ?)
                """)
                .setParameter(1, stopId).setParameter(2, routeId)
                .setParameter(3, (short) (maxSeq.intValue() + 1))
                .setParameter(4, lat).setParameter(5, lng)
                .setParameter(6, placeholder).setParameter(7, placeholder).setParameter(8, serviceSeconds)
                .setParameter(9, promisedStart).setParameter(10, promisedEnd)
                .executeUpdate();
        return stopId;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void rewrite(UUID routeId, PlannedRoute route) {
        // 순번을 피신시키지 않는다. (route_id, seq) UNIQUE 는 V3 에서 지연 제약이 되었고
        // (DEFERRABLE INITIALLY DEFERRED), 검사는 커밋 시점에 한 번만 일어난다 — 중간에 두 행이
        // 같은 순번을 갖는 순간은 애초에 지켜야 하는 불변식이 아니다.
        Map<String, UUID> byPoint = livePointsOf(routeId);

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

        // 취소된 stop 은 계획에 없다(loadStops 가 뺐다). 순번을 주지 않으면 옛 번호가 위에서
        // 새로 부여한 것과 겹쳐 커밋이 터진다. 뒤로 보내는 이유: 재배정은 이미 순번을 다시
        // 매기는 조작이고, 방문하지 않는 지점의 순번은 기사에게 아무것도 지시하지 않는다.
        // (취소 자체는 순번을 건드리지 않는다 — retime 을 보라.)
        int next = route.stops().size() + 1;
        for (UUID stopId : cancelledStopsBySeq(routeId)) {
            entityManager.createNativeQuery("UPDATE route_stops SET seq = ? WHERE id = ?")
                    .setParameter(1, (short) next++).setParameter(2, stopId).executeUpdate();
        }

        // stop_count 는 배열 길이여야 한다 — route.assigned 의 summary.stopCount 가 그것이고,
        // 취소된 stop 도 페이로드에 실린다 (§6.10).
        entityManager.createNativeQuery("""
                UPDATE routes SET stop_count = (
                           SELECT count(*) FROM route_stops WHERE route_id = routes.id),
                       distance_m = ?, duration_s = ?, cost_krw = ?
                 WHERE id = ?
                """)
                .setParameter(1, route.distanceM())
                .setParameter(2, route.durationS())
                .setParameter(3, route.cost().krw())
                .setParameter(4, routeId).executeUpdate();
    }

    /** 취소된 stop 들, 지금 순번 순서대로. */
    @SuppressWarnings("unchecked")
    private List<UUID> cancelledStopsBySeq(UUID routeId) {
        return entityManager.createNativeQuery("""
                SELECT id FROM route_stops
                 WHERE route_id = ? AND status = 'CANCELLED'
                 ORDER BY seq
                """).setParameter(1, routeId).getResultList();
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

    @Override
    @SuppressWarnings("unchecked")
    public Optional<AssignedStop> findAssignedStop(UUID orderId) {
        // 같은 주문이 두 계획의 라우트에 남아 있을 수 있다(부분 재계획은 옛 라우트를 지우지
        // 않는다). id 가 UUIDv7 이라 시간순이므로 가장 나중에 만들어진 stop 이 지금 유효한
        // 것이다 — 불변규칙 10 이 여기서 정렬 기준으로 값을 한다.
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT s.route_id, s.id, s.seq, s.status
                  FROM route_stops s
                  JOIN route_stop_orders o ON o.stop_id = s.id
                 WHERE o.order_id = ?
                 ORDER BY s.id DESC
                 LIMIT 1
                """).setParameter(1, orderId).getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.getFirst();
        return Optional.of(new AssignedStop((UUID) row[0], (UUID) row[1],
                ((Number) row[2]).intValue(), RouteStopStatus.valueOf((String) row[3])));
    }

    @Override
    public void markStopStatus(UUID stopId, RouteStopStatus status, Instant actualAt) {
        // COALESCE 가 「처음 닿은 시각」을 지킨다 (ADR-048 결정 1). 값이 이미 있으면 그대로 두고,
        // 없을 때만 쓴다 — 덮으면 이 컬럼은 도착이 아니라 완료를 재게 되고, 그 변화는 값을
        // 보아서는 알 수 없다. 규칙이 한 줄의 SQL 인 이유는 읽고-판단하고-쓰는 세 걸음이
        // 같은 것을 하면서 경합 창만 만들기 때문이다.
        entityManager.createNativeQuery("""
                UPDATE route_stops SET status = ?, actual_at = COALESCE(actual_at, ?) WHERE id = ?
                """)
                .setParameter(1, status.name())
                .setParameter(2, actualAt)
                .setParameter(3, stopId)
                .executeUpdate();
    }

    @Override
    public boolean tryStartReplan(UUID routeId, Instant now, Duration cooldown) {
        // 비교와 갱신이 한 문장이다 (§6.8 5단계). 읽고 나서 쓰면 두 소비자가 같은 값을 읽는
        // 창이 생기고, 막아야 하는 것이 바로 그 창이다 — 두 at-risk 는 eventId 가 달라
        // processed_events 에게는 둘 다 처음 보는 이벤트다 (ADR-046 결정 3).
        return entityManager.createNativeQuery("""
                UPDATE routes SET last_replanned_at = ?
                 WHERE id = ? AND (last_replanned_at IS NULL OR last_replanned_at <= ?)
                """)
                .setParameter(1, now)
                .setParameter(2, routeId)
                .setParameter(3, now.minus(cooldown))
                .executeUpdate() == 1;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<SettledStop> lastSettledStop(UUID routeId) {
        // 「마지막」은 actual_at 이 아니라 seq 다 — 순서가 뒤바뀐 보고가 있어도 기사가 서 있는
        // 자리는 순번이 가장 큰 «닿은» stop 이고, §6.8 의 얼어 있는 앞자락이 거기까지다.
        // UNIQUE (route_id, seq) 를 역순으로 한 건 읽는다. 새 인덱스는 없다 (불변규칙 11).
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT seq, planned_arrival, actual_at FROM route_stops
                 WHERE route_id = ? AND actual_at IS NOT NULL
                 ORDER BY seq DESC
                 LIMIT 1
                """).setParameter(1, routeId).getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.getFirst();
        return Optional.of(new SettledStop(((Number) row[0]).intValue(), (Instant) row[1],
                (Instant) row[2]));
    }

    @Override
    public boolean cancelStopIfAllOrdersCancelled(UUID stopId) {
        // 술어를 리터럴로 적는다 (CLAUDE.md 코딩 컨벤션). 여기서는 부분 인덱스 때문이 아니라
        // 상태 문자열이 스키마의 값이고 파라미터로 받을 이유가 없기 때문이다.
        // 후보가 없는 주문은 취소가 아니다 — 내부 조인이던 때는 그 주문이 NOT EXISTS 에서 빠져 「전부 취소됐다」가
        // 참이 됐다. 여기서는 stop 을 살려 두고, 뒤따르는 시각 재계산(loadStops)이 409 로 되돌린다(ADR-059 결정 3).
        return entityManager.createNativeQuery("""
                UPDATE route_stops s SET status = 'CANCELLED'
                 WHERE s.id = ? AND s.status = 'PLANNED'
                   AND NOT EXISTS (
                       SELECT 1 FROM route_stop_orders o
                         LEFT JOIN dispatch_candidates c ON c.order_id = o.order_id
                        WHERE o.stop_id = s.id AND (c.order_id IS NULL OR c.status <> 'CANCELLED'))
                """).setParameter(1, stopId).executeUpdate() == 1;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void retime(UUID routeId, @Nullable PlannedRoute route) {
        if (route == null) {
            // 살아 있는 stop 이 하나도 없다. 라우트는 남지만 아무 데도 가지 않는다 —
            // 요약을 0 으로 두지 않으면 운영 화면이 죽은 라우트를 비용과 함께 보여 준다.
            entityManager.createNativeQuery("""
                    UPDATE routes SET distance_m = 0, duration_s = 0, cost_krw = 0 WHERE id = ?
                    """).setParameter(1, routeId).executeUpdate();
            return;
        }

        Map<String, UUID> byPoint = livePointsOf(routeId);
        for (PlannedStop planned : route.stops()) {
            UUID stopId = byPoint.get(key(planned.stop().point()));
            if (stopId == null) {
                throw new IllegalStateException("시각을 다시 쓸 stop 을 찾지 못했습니다: " + planned.seq());
            }
            // seq 는 건드리지 않는다 — 기사가 보던 순번이다 (§6.10).
            entityManager.createNativeQuery("""
                    UPDATE route_stops SET planned_arrival = ?, planned_departure = ? WHERE id = ?
                    """)
                    .setParameter(1, planned.arrival()).setParameter(2, planned.departure())
                    .setParameter(3, stopId).executeUpdate();
        }
        entityManager.createNativeQuery("""
                UPDATE routes SET distance_m = ?, duration_s = ?, cost_krw = ? WHERE id = ?
                """)
                .setParameter(1, route.distanceM()).setParameter(2, route.durationS())
                .setParameter(3, route.cost().krw()).setParameter(4, routeId).executeUpdate();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<RouteSnapshot> snapshot(UUID routeId) {
        List<Object[]> header = entityManager.createNativeQuery("""
                SELECT vehicle_id, distance_m, duration_s, cost_krw, planned_departure
                  FROM routes WHERE id = ?
                """).setParameter(1, routeId).getResultList();
        if (header.isEmpty()) {
            return Optional.empty();
        }

        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT s.id, s.seq, s.lat, s.lng, s.planned_arrival, s.service_s, s.status,
                       o.order_id, c.status, s.promised_start, s.promised_end
                  FROM route_stops s
                  JOIN route_stop_orders o ON o.stop_id = s.id
                  LEFT JOIN dispatch_candidates c ON c.order_id = o.order_id
                 WHERE s.route_id = ?
                 ORDER BY s.seq, o.order_id
                """).setParameter(1, routeId).getResultList();

        Map<UUID, SnapshotBuilder> byStop = new LinkedHashMap<>();
        for (Object[] row : rows) {
            if (row[8] == null) {
                // 후보가 없다 — 빼면 발행된 개정에서 그 stop 이 사라진다(ADR-059 결정 3).
                throw DispatchErrorCode.candidatesExpired(routeId, (UUID) row[7]);
            }
            SnapshotBuilder builder = byStop.computeIfAbsent((UUID) row[0], id -> new SnapshotBuilder(
                    ((Number) row[1]).intValue(),
                    ((BigDecimal) row[2]).doubleValue(), ((BigDecimal) row[3]).doubleValue(),
                    (Instant) row[4], ((Number) row[5]).intValue(),
                    "CANCELLED".equals((String) row[6]),
                    // V6 이전 행은 둘 다 NULL 이다. 후보 테이블에서 끌어오지 않는다 —
                    // 그 출처는 다른 애그리거트의 보존 정책에 매달린다(§5.3).
                    row[9] == null || row[10] == null
                            ? null : new TimeWindow((Instant) row[9], (Instant) row[10])));
            builder.add((UUID) row[7], "CANCELLED".equals((String) row[8]));
        }

        Object[] first = header.getFirst();
        return Optional.of(new RouteSnapshot(routeId, (UUID) first[0],
                ((Number) first[1]).intValue(), ((Number) first[2]).intValue(),
                ((Number) first[3]).longValue(),
                // V7 이전 행은 NULL 이다. 지어내지 않는다 (V6 의 약속창과 같은 규칙).
                (Instant) first[4],
                byStop.values().stream().map(SnapshotBuilder::build).toList()));
    }

    /** 라우트의 살아 있는 지점 → stop id. 취소된 stop 은 다시 쓸 대상이 아니다. */
    @SuppressWarnings("unchecked")
    private Map<String, UUID> livePointsOf(UUID routeId) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT s.id, s.lat, s.lng FROM route_stops s
                 WHERE s.route_id = ? AND s.status <> 'CANCELLED'
                """).setParameter(1, routeId).getResultList();
        Map<String, UUID> byPoint = new LinkedHashMap<>();
        for (Object[] row : rows) {
            byPoint.put(key((BigDecimal) row[1], (BigDecimal) row[2]), (UUID) row[0]);
        }
        return byPoint;
    }

    /** 스냅샷의 stop 하나를 여러 행에서 모은다. */
    private static final class SnapshotBuilder {

        private final int seq;
        private final double lat;
        private final double lng;
        private final Instant arrival;
        private final int serviceSeconds;
        private final boolean cancelled;
        private final @Nullable TimeWindow promised;
        private final List<UUID> orderIds = new ArrayList<>();
        private final List<UUID> cancelledOrderIds = new ArrayList<>();

        private SnapshotBuilder(int seq, double lat, double lng, Instant arrival,
                int serviceSeconds, boolean cancelled, @Nullable TimeWindow promised) {
            this.promised = promised;
            this.seq = seq;
            this.lat = lat;
            this.lng = lng;
            this.arrival = arrival;
            this.serviceSeconds = serviceSeconds;
            this.cancelled = cancelled;
        }

        private void add(UUID orderId, boolean orderCancelled) {
            orderIds.add(orderId);
            if (orderCancelled) {
                cancelledOrderIds.add(orderId);
            }
        }

        private RouteSnapshot.StopSnapshot build() {
            return new RouteSnapshot.StopSnapshot(seq, orderIds, cancelledOrderIds, lat, lng,
                    arrival, serviceSeconds, cancelled, promised);
        }
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
