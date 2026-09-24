package com.dawnline.ops.adapter.out.persistence;

import static com.dawnline.ops.adapter.out.persistence.JdbcOrderRows.enumOf;
import static com.dawnline.ops.adapter.out.persistence.JdbcOrderRows.instantOf;

import com.dawnline.ops.application.port.out.DeliveryKpis;
import com.dawnline.ops.application.port.out.ReadModelViews;
import com.dawnline.ops.domain.RouteStatus;
import com.dawnline.ops.domain.WaveStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 읽기 모델 조회 어댑터 (DESIGN.md §5.5 「조회」). 전부 읽기이고 트랜잭션을 요구하지 않는다.
 *
 * <p>인덱스를 더하지 않는다 — 판단과 행 수는 {@code docs/benchmarks/phase6-ops-read-surface.md} 에 있다(불변규칙 11).
 * 예외 목록만 기존 인덱스({@code ix_rmo_delivery_hour})를 타도록 버킷 식을 인덱스의 식 그대로 적는다.
 */
public class JdbcReadModelViews implements ReadModelViews {

    static final String CAMPS_SQL = """
            SELECT camp_id, count(*) AS waves, max(cutoff_at) AS latest_cutoff_at
              FROM rm_waves
             WHERE camp_id IS NOT NULL
             GROUP BY camp_id
             ORDER BY camp_id
            """;

    static final String WAVES_SQL = """
            SELECT wave_id, service_tier, cutoff_at, status, order_count, plan_id, plan_duration_ms,
                   total_cost_krw, unassigned_count, route_count
              FROM rm_waves
             WHERE camp_id = ? AND cutoff_at >= ? AND cutoff_at < ?
             ORDER BY cutoff_at, service_tier, wave_id
             LIMIT ?
            """;

    static final String WAVE_PLAN_SQL = "SELECT wave_id, plan_id, depot_lat, depot_lng FROM rm_waves WHERE wave_id = ?";

    static final String ROUTES_SQL = """
            SELECT route_id, vehicle_id, revision, status, planned_departure, departed_at, stop_count,
                   completed_count, failed_count, at_risk, distance_m, cost_krw
              FROM rm_routes
             WHERE plan_id = ?
             ORDER BY route_id
            """;

    /**
     * 버킷 식은 {@code ix_rmo_delivery_hour} 의 식 그대로다 — 글자가 다르면 인덱스를 못 탄다(V2 머리말). 술어의 상태
     * 두 칸은 리터럴로 적는다(CLAUDE.md 코딩 컨벤션). 공개인 이유는 {@code KpiViewsIndexIT} 가 이 문장 그대로의 계획을
     * 보기 때문이다 — 사본을 보면 사본의 계획을 증명한다.
     */
    public static final String CANCELLED_BUT_DELIVERED_SQL = """
            SELECT order_id, wave_id, route_id, delivered_at
              FROM rm_orders
             WHERE camp_id = ?
               AND date_trunc('hour', COALESCE(delivered_at, failed_at), 'UTC') >= ?
               AND date_trunc('hour', COALESCE(delivered_at, failed_at), 'UTC') <= ?
               AND order_status = 'CANCELLED' AND delivery_outcome = 'COMPLETED'
             ORDER BY delivered_at DESC, order_id
             LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    /**
     * @param jdbc JDBC 템플릿
     */
    public JdbcReadModelViews(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<CampSummary> camps() {
        return jdbc.query(CAMPS_SQL, (rs, n) -> new CampSummary(rs.getObject("camp_id", UUID.class),
                rs.getLong("waves"), instantOf(rs, "latest_cutoff_at")));
    }

    @Override
    public List<WaveSummary> waves(UUID campId, Instant from, Instant to, int limit) {
        return jdbc.query(WAVES_SQL, (rs, n) -> new WaveSummary(
                        rs.getObject("wave_id", UUID.class),
                        rs.getString("service_tier"),
                        instantOf(rs, "cutoff_at"),
                        enumOf(WaveStatus.class, rs.getString("status")),
                        rs.getObject("order_count", Integer.class),
                        rs.getObject("plan_id", UUID.class),
                        rs.getObject("plan_duration_ms", Integer.class),
                        rs.getObject("total_cost_krw", Long.class),
                        rs.getObject("unassigned_count", Integer.class),
                        rs.getObject("route_count", Integer.class)),
                campId, utc(from), utc(to), limit);
    }

    @Override
    public Optional<WavePlan> wavePlan(UUID waveId) {
        return jdbc.query(WAVE_PLAN_SQL, (rs, n) -> {
            BigDecimal lat = rs.getBigDecimal("depot_lat");
            BigDecimal lng = rs.getBigDecimal("depot_lng");
            // V3 의 제약이 둘을 함께 두므로 하나만 보고 판정해도 되지만, 둘 다 보는 편이 읽기 쉽다.
            Depot depot = lat == null || lng == null ? null : new Depot(lat.doubleValue(), lng.doubleValue());
            return new WavePlan(rs.getObject("wave_id", UUID.class), rs.getObject("plan_id", UUID.class), depot);
        }, waveId).stream().findFirst();
    }

    @Override
    public List<RouteSummary> routesOf(UUID planId) {
        return jdbc.query(ROUTES_SQL, (rs, n) -> new RouteSummary(
                        rs.getObject("route_id", UUID.class),
                        rs.getObject("vehicle_id", UUID.class),
                        rs.getObject("revision", Integer.class),
                        enumOf(RouteStatus.class, rs.getString("status")),
                        instantOf(rs, "planned_departure"),
                        instantOf(rs, "departed_at"),
                        rs.getObject("stop_count", Integer.class),
                        rs.getObject("completed_count", Integer.class),
                        rs.getObject("failed_count", Integer.class),
                        rs.getObject("at_risk", Boolean.class),
                        rs.getObject("distance_m", Integer.class),
                        rs.getObject("cost_krw", Integer.class)),
                planId);
    }

    @Override
    public List<CancelledButDelivered> cancelledButDelivered(UUID campId, DeliveryKpis.Buckets buckets, int limit) {
        return jdbc.query(CANCELLED_BUT_DELIVERED_SQL, (rs, n) -> new CancelledButDelivered(
                        rs.getObject("order_id", UUID.class),
                        rs.getObject("wave_id", UUID.class),
                        rs.getObject("route_id", UUID.class),
                        Objects.requireNonNull(instantOf(rs, "delivered_at"), "delivered_at")),
                campId, utc(buckets.first()), utc(buckets.last()), limit);
    }

    /** TIMESTAMPTZ 에는 OffsetDateTime 으로 넘긴다 — 드라이버가 Instant 를 직접 받지 않는다. */
    private static Object utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
