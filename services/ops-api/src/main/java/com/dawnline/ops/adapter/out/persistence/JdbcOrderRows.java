package com.dawnline.ops.adapter.out.persistence;

import com.dawnline.ops.application.port.out.OrderColumn;
import com.dawnline.ops.application.port.out.OrderRows;
import com.dawnline.ops.application.port.out.Patch;
import com.dawnline.ops.domain.DeliveryOutcome;
import com.dawnline.ops.domain.OrderStatus;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code rm_orders} 어댑터 (DESIGN.md §5.5, ADR-051).
 *
 * <h2>잠금은 두 문장이다</h2>
 * {@code INSERT … ON CONFLICT DO NOTHING} 으로 없는 행을 <strong>키만으로</strong> 만들고, 이어서
 * {@code SELECT … ORDER BY order_id FOR UPDATE} 로 전부를 키 순서로 잠근다. 앞의 문장도 키 순서로
 * 넣는다 — 같은 새 키를 넣는 두 트랜잭션은 뒤의 것이 앞의 것의 커밋을 기다리므로, 여러 키를 넣는
 * 순서가 트랜잭션마다 다르면 거기서 교착한다.
 *
 * <p>「행을 만드는 핸들러」를 두지 않는다는 결정(ADR-051 결정 1)이 여기서 구현된다 — 모든 사실이
 * 이 잠금을 지나므로 행은 언제나 먼저 온 사실이 만든다.
 */
public class JdbcOrderRows implements OrderRows {

    /** 키와 나이만으로 — 나이는 사실이 아니라 프로젝션의 기록이라 여기서 적는다(ADR-058 결정 4). */
    private static final String ENSURE_SQL = """
            INSERT INTO rm_orders (order_id, updated_at)
            SELECT DISTINCT id, ?::timestamptz FROM unnest(?::uuid[]) AS t(id) ORDER BY id
            ON CONFLICT (order_id) DO NOTHING
            """;

    private static final String LOCK_SQL = """
            SELECT order_id, order_status, delivery_outcome, route_id, planned_as_of, eta_as_of
              FROM rm_orders
             WHERE order_id = ANY (?::uuid[])
             ORDER BY order_id
               FOR UPDATE
            """;

    private final JdbcTemplate jdbc;

    /**
     * @param jdbc 같은 트랜잭션에 참여하는 JDBC 템플릿
     */
    public JdbcOrderRows(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Map<UUID, OrderRow> lock(Collection<UUID> orderIds, Instant touchedAt) {
        Objects.requireNonNull(orderIds, "orderIds");
        Map<UUID, OrderRow> rows = new HashMap<>();
        if (orderIds.isEmpty()) {
            return rows;
        }
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(ENSURE_SQL);
            statement.setObject(1, PatchStatements.bindable(Objects.requireNonNull(touchedAt, "touchedAt")));
            statement.setArray(2, UuidArrays.of(connection, orderIds));
            return statement;
        });
        jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(LOCK_SQL);
            statement.setArray(1, UuidArrays.of(connection, orderIds));
            return statement;
        }, (ResultSet rs) -> {
            rows.put(rs.getObject("order_id", UUID.class), new OrderRow(
                    enumOf(OrderStatus.class, rs.getString("order_status")),
                    enumOf(DeliveryOutcome.class, rs.getString("delivery_outcome")),
                    rs.getObject("route_id", UUID.class),
                    instantOf(rs, "planned_as_of"),
                    instantOf(rs, "eta_as_of")));
        });
        return rows;
    }

    @Override
    public void write(UUID orderId, Patch<OrderColumn> patch, Instant touchedAt) {
        PatchStatements.apply(jdbc, "rm_orders", "order_id", orderId, patch,
                Map.of("updated_at", touchedAt));
    }

    static <E extends Enum<E>> @Nullable E enumOf(Class<E> type, @Nullable String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    static @Nullable Instant instantOf(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
