package com.dawnline.ops;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 읽기 모델 표를 <strong>카탈로그에서</strong> 읽는다 — 표도 칸도 열거하지 않는다(§13 규칙 2).
 *
 * <p>순서 검사의 비교 대상은 {@code rm_} 로 시작하는 표 전부의 칸 전부이고, 빼는 칸은 호출자가
 * 이유와 함께 넘긴다. 표나 칸이 늘면 이 검사가 저절로 그것을 본다 — ADR-051 결정 6 의 「토픽을
 * 빼는 방식으로」를 칸에도 적용한 것이다.
 */
final class ReadModelTables {

    private final JdbcTemplate jdbc;

    ReadModelTables(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code rm_} 로 시작하는 표 전부. */
    List<String> tables() {
        return jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = current_schema() AND table_name LIKE 'rm\\_%'
                 ORDER BY table_name
                """, String.class);
    }

    /** 표의 칸 전부 (선언 순서). */
    List<String> columns(String table) {
        return jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = current_schema() AND table_name = ?
                 ORDER BY ordinal_position
                """, String.class, table);
    }

    /** 생성 칸 — 핸들러가 쓰지 않는 칸이다. */
    List<String> generatedColumns(String table) {
        return jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = current_schema() AND table_name = ? AND is_generated = 'ALWAYS'
                """, String.class, table);
    }

    /** 기본 키 칸 (키 순서). */
    List<String> primaryKey(String table) {
        return jdbc.queryForList("""
                SELECT k.column_name
                  FROM information_schema.table_constraints c
                  JOIN information_schema.key_column_usage k
                    ON k.constraint_name = c.constraint_name AND k.table_schema = c.table_schema
                 WHERE c.table_schema = current_schema() AND c.table_name = ? AND c.constraint_type = 'PRIMARY KEY'
                 ORDER BY k.ordinal_position
                """, String.class, table);
    }

    /**
     * 키의 첫 칸이 {@code keys} 에 드는 행만 — 표 → 키 → 칸 → 값.
     *
     * @param keys     이 검사가 만든 행의 키들 (다른 IT 의 행은 보지 않는다)
     * @param excluded 비교에서 빼는 칸
     */
    Map<String, Map<Object, Map<String, Object>>> snapshot(Collection<UUID> keys, Set<String> excluded) {
        Map<String, Map<Object, Map<String, Object>>> tables = new TreeMap<>();
        for (String table : tables()) {
            List<String> key = primaryKey(table);
            List<String> columns = new ArrayList<>(columns(table));
            columns.removeAll(excluded);
            Map<Object, Map<String, Object>> rows = new LinkedHashMap<>();
            jdbc.query(connection -> {
                var statement = connection.prepareStatement("SELECT " + String.join(", ", columns) + " FROM "
                        + table + " WHERE " + key.getFirst() + " = ANY (?)");
                statement.setArray(1, uuids(connection, keys));
                return statement;
            }, rs -> {
                Map<String, Object> row = new TreeMap<>();
                for (String column : columns) {
                    Object value = rs.getObject(column);
                    if (value != null) {
                        row.put(column, value);
                    }
                }
                Object rowKey = key.size() == 1 ? rs.getObject(key.getFirst())
                        : key.stream().map(k -> {
                            try {
                                return rs.getObject(k);
                            } catch (java.sql.SQLException e) {
                                throw new IllegalStateException(e);
                            }
                        }).toList();
                rows.put(rowKey, row);
            });
            tables.put(table, rows);
        }
        return tables;
    }

    /**
     * 키의 첫 칸이 {@code keys} 에 드는 행을 지운다 — 되돌리지 않고 지운다(CLAUDE.md).
     *
     * @param keys 이 검사가 만든 행의 키들
     */
    void delete(Collection<UUID> keys) {
        for (String table : tables()) {
            String first = primaryKey(table).getFirst();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("DELETE FROM " + table + " WHERE " + first + " = ANY (?)");
                statement.setArray(1, uuids(connection, keys));
                return statement;
            });
        }
    }

    private static Array uuids(java.sql.Connection connection, Collection<UUID> keys) throws java.sql.SQLException {
        return connection.createArrayOf("uuid", keys.toArray());
    }
}
