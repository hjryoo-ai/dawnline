package com.dawnline.ops.adapter.out.persistence;

import com.dawnline.ops.application.port.out.Patch;
import com.dawnline.ops.application.port.out.WaveColumn;
import com.dawnline.ops.application.port.out.WaveRows;
import com.dawnline.ops.domain.WaveStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code rm_waves} 어댑터. 잠금의 모양은 {@link JdbcOrderRows} 와 같다 — 한 사실이 웨이브를
 * 하나만 잠그므로 순서 문제는 없다.
 */
public class JdbcWaveRows implements WaveRows {

    private static final String ENSURE_SQL =
            "INSERT INTO rm_waves (wave_id, updated_at) VALUES (?, ?) ON CONFLICT (wave_id) DO NOTHING";

    private static final String LOCK_SQL = "SELECT status FROM rm_waves WHERE wave_id = ? FOR UPDATE";

    /** 편입된 것으로 알려진 주문 수 (ADR-051 결정 4). */
    static final String RECOUNT_SQL = """
            UPDATE rm_waves w
               SET order_count = (SELECT count(*) FROM rm_orders o WHERE o.wave_id = w.wave_id)
             WHERE w.wave_id = ?
            """;

    private final JdbcTemplate jdbc;

    /**
     * @param jdbc 같은 트랜잭션에 참여하는 JDBC 템플릿
     */
    public JdbcWaveRows(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public WaveRow lock(UUID waveId, Instant touchedAt) {
        Objects.requireNonNull(waveId, "waveId");
        jdbc.update(ENSURE_SQL, waveId, PatchStatements.bindable(Objects.requireNonNull(touchedAt, "touchedAt")));
        String status = jdbc.queryForObject(LOCK_SQL, String.class, waveId);
        return new WaveRow(JdbcOrderRows.enumOf(WaveStatus.class, status));
    }

    @Override
    public void write(UUID waveId, Patch<WaveColumn> patch, Instant touchedAt) {
        PatchStatements.apply(jdbc, "rm_waves", "wave_id", waveId, patch, Map.of("updated_at", touchedAt));
    }

    @Override
    public void recountOrders(UUID waveId) {
        jdbc.update(RECOUNT_SQL, Objects.requireNonNull(waveId, "waveId"));
    }
}
