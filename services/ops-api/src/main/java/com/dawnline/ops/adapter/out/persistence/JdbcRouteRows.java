package com.dawnline.ops.adapter.out.persistence;

import com.dawnline.ops.application.port.out.Patch;
import com.dawnline.ops.application.port.out.RouteColumn;
import com.dawnline.ops.application.port.out.RouteRows;
import com.dawnline.ops.domain.RouteStatus;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code rm_routes} 어댑터. 잠금의 모양은 {@link JdbcOrderRows} 와 같다.
 *
 * <p>개수 칸은 다시 센다(ADR-051 결정 4). 상태 값은 <strong>리터럴</strong>로 적는다 — 바인드로
 * 넣을 이유가 없고, 인덱스 판단을 할 때 질의가 한 모양이어야 한다(CLAUDE.md 부분 인덱스 규칙과
 * 같은 습관).
 */
public class JdbcRouteRows implements RouteRows {

    private static final String ENSURE_SQL = """
            INSERT INTO rm_routes (route_id, updated_at)
            SELECT DISTINCT id, ?::timestamptz FROM unnest(?::uuid[]) AS t(id) ORDER BY id
            ON CONFLICT (route_id) DO NOTHING
            """;

    private static final String LOCK_SQL = """
            SELECT route_id, revision, status
              FROM rm_routes
             WHERE route_id = ANY (?::uuid[])
             ORDER BY route_id
               FOR UPDATE
            """;

    /**
     * 계획이 도착한 라우트만 센다 — 소속을 모르는 동안의 0 은 부재를 값으로 적는 것이다.
     *
     * <p>세 칸을 한 번의 탐색으로 낸다. {@code completed_at} 은 결과 없는 비취소 주문이 남았으면 {@code NULL}, 아니면 마지막
     * 결과 시각이고, 결과가 하나도 없으면(빈 라우트 · 전부 취소) 계획 출발이다 — 전부 사실이라 처리 순서를 타지 않는다
     * (ADR-061). 주문이 없으면 집계가 한 행({@code bool_or} 는 {@code NULL})을 내므로 {@code ELSE} 로 간다.
     */
    static final String RECOUNT_SQL = """
            UPDATE rm_routes r
               SET (completed_count, failed_count, completed_at) = (
                     SELECT count(*) FILTER (WHERE o.delivery_outcome = 'COMPLETED'),
                            count(*) FILTER (WHERE o.delivery_outcome = 'FAILED'),
                            CASE WHEN bool_or(o.delivery_outcome IS NULL
                                              AND o.order_status IS DISTINCT FROM 'CANCELLED') THEN NULL
                                 ELSE COALESCE(max(COALESCE(o.delivered_at, o.failed_at)), r.planned_departure)
                            END
                       FROM rm_orders o
                      WHERE o.route_id = r.route_id)
             WHERE r.route_id = ANY (?::uuid[])
               AND r.revision IS NOT NULL
            """;

    private final JdbcTemplate jdbc;

    /**
     * @param jdbc 같은 트랜잭션에 참여하는 JDBC 템플릿
     */
    public JdbcRouteRows(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Map<UUID, RouteRow> lock(Collection<UUID> routeIds, Instant touchedAt) {
        Objects.requireNonNull(routeIds, "routeIds");
        Map<UUID, RouteRow> rows = new HashMap<>();
        if (routeIds.isEmpty()) {
            return rows;
        }
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(ENSURE_SQL);
            statement.setObject(1, PatchStatements.bindable(Objects.requireNonNull(touchedAt, "touchedAt")));
            statement.setArray(2, UuidArrays.of(connection, routeIds));
            return statement;
        });
        jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(LOCK_SQL);
            statement.setArray(1, UuidArrays.of(connection, routeIds));
            return statement;
        }, (ResultSet rs) -> {
            rows.put(rs.getObject("route_id", UUID.class), new RouteRow(
                    rs.getObject("revision", Integer.class),
                    JdbcOrderRows.enumOf(RouteStatus.class, rs.getString("status"))));
        });
        return rows;
    }

    @Override
    public void write(UUID routeId, Patch<RouteColumn> patch, Instant touchedAt) {
        PatchStatements.apply(jdbc, "rm_routes", "route_id", routeId, patch, Map.of("updated_at", touchedAt));
    }

    @Override
    public void recount(Collection<UUID> routeIds) {
        Objects.requireNonNull(routeIds, "routeIds");
        if (routeIds.isEmpty()) {
            return;
        }
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(RECOUNT_SQL);
            statement.setArray(1, UuidArrays.of(connection, routeIds));
            return statement;
        });
    }
}
