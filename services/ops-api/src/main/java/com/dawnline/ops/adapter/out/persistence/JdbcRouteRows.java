package com.dawnline.ops.adapter.out.persistence;

import com.dawnline.ops.application.port.out.Patch;
import com.dawnline.ops.application.port.out.RouteColumn;
import com.dawnline.ops.application.port.out.RouteRows;
import com.dawnline.ops.domain.RouteStatus;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
            INSERT INTO rm_routes (route_id)
            SELECT DISTINCT id FROM unnest(?::uuid[]) AS t(id) ORDER BY id
            ON CONFLICT (route_id) DO NOTHING
            """;

    private static final String LOCK_SQL = """
            SELECT route_id, revision, status
              FROM rm_routes
             WHERE route_id = ANY (?::uuid[])
             ORDER BY route_id
               FOR UPDATE
            """;

    /** 계획이 도착한 라우트만 센다 — 소속을 모르는 동안의 0 은 부재를 값으로 적는 것이다. */
    static final String RECOUNT_SQL = """
            UPDATE rm_routes r
               SET completed_count = (SELECT count(*) FROM rm_orders o
                                       WHERE o.route_id = r.route_id AND o.delivery_outcome = 'COMPLETED'),
                   failed_count    = (SELECT count(*) FROM rm_orders o
                                       WHERE o.route_id = r.route_id AND o.delivery_outcome = 'FAILED')
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
    public Map<UUID, RouteRow> lock(Collection<UUID> routeIds) {
        Objects.requireNonNull(routeIds, "routeIds");
        Map<UUID, RouteRow> rows = new HashMap<>();
        if (routeIds.isEmpty()) {
            return rows;
        }
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(ENSURE_SQL);
            statement.setArray(1, UuidArrays.of(connection, routeIds));
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
    public void write(UUID routeId, Patch<RouteColumn> patch) {
        PatchStatements.apply(jdbc, "rm_routes", "route_id", routeId, patch, Map.of());
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
