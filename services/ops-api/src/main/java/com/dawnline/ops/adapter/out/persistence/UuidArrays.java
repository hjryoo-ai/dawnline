package com.dawnline.ops.adapter.out.persistence;

import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.UUID;

/** {@code uuid[]} 바인딩. */
final class UuidArrays {

    private UuidArrays() {
    }

    /**
     * @param connection 커넥션
     * @param ids        키들 (중복은 걷어 낸다)
     * @return {@code uuid[]}
     * @throws SQLException 드라이버 오류
     */
    static Array of(Connection connection, Collection<UUID> ids) throws SQLException {
        return connection.createArrayOf("uuid", new LinkedHashSet<>(ids).toArray());
    }
}
