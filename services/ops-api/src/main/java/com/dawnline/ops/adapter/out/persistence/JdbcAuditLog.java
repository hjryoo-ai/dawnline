package com.dawnline.ops.adapter.out.persistence;

import com.dawnline.ops.application.port.out.AuditLog;
import com.dawnline.ops.domain.AuditResult;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code audit_logs} (V1, DESIGN.md §5.5).
 *
 * <p>{@link #open} · {@link #close} 는 호출자의 트랜잭션 없이 돈다 — {@link JdbcTemplate} 는 트랜잭션 밖에서 자동 커밋이다.
 * {@link AuditLog} 가 요구하는 「{@code PENDING} 이 위임 전에 커밋된다」가 그것으로 성립한다. 해소의 셋({@link #lockForResolution} ·
 * {@link #findResolution} · {@link #record})은 반대로 호출자의 트랜잭션에 참여한다 — 잠금이 삽입까지 이어져야 한다(ADR-065).
 *
 * <p>{@link #close} 는 {@code PENDING} 행만 닫는다. 결과는 한 번만 적힌다 — 닫힌 행을 다시 닫으려는
 * 것은 버그이고, 조용히 덮으면 먼저 적힌 결과가 사라진다.
 */
public class JdbcAuditLog implements AuditLog {

    private static final String INSERT = """
            INSERT INTO audit_logs (id, actor, action, target_type, target_id, request, result, created_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, 'PENDING', ?)
            """;

    private static final String CLOSE = "UPDATE audit_logs SET result = ? WHERE id = ? AND result = 'PENDING'";

    private static final String RECORD = """
            INSERT INTO audit_logs (id, actor, action, target_type, target_id, request, result, created_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """;

    private static final String LOCK = "SELECT id, action, result, created_at FROM audit_logs WHERE id = ? FOR UPDATE";

    /**
     * 해소 행 찾기. {@code target_id} 에 인덱스가 없어 순차 스캔이다 — 행 수가 운영자 커맨드 수라 지금 규모에서 맞다(ADR-065
     * 재검토 지점: 수만 행). 술어의 상수는 리터럴로 적는다(CLAUDE.md — 인덱스가 생기면 부분 인덱스일 것이다).
     */
    private static final String FIND_RESOLUTION = """
            SELECT id FROM audit_logs
             WHERE action = 'RESOLVE_AUDIT' AND result = 'SUCCEEDED' AND target_id = ?
             ORDER BY created_at LIMIT 1
            """;

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    /**
     * @param jdbc JDBC 템플릿 — 트랜잭션 밖에서 부른다
     * @param json {@code request} JSONB 직렬화
     */
    public JdbcAuditLog(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public void open(Entry entry) {
        jdbc.update(INSERT, entry.id(), entry.actor(), entry.action(), entry.targetType(), entry.targetId(),
                json.writeValueAsString(entry.request()), Timestamp.from(entry.createdAt()));
    }

    @Override
    public void close(UUID id, AuditResult result) {
        if (result == AuditResult.PENDING) {
            throw new IllegalArgumentException("PENDING 으로 닫을 수 없다 — 닫는 것은 결과다");
        }
        int updated = jdbc.update(CLOSE, result.name(), id);
        if (updated != 1) {
            throw new IllegalStateException("PENDING 인 감사 행이 아니다: " + id);
        }
    }

    @Override
    public void record(Entry entry, AuditResult result) {
        if (result == AuditResult.PENDING) {
            throw new IllegalArgumentException("record 는 결과가 정해진 행이다 — PENDING 은 open 으로 쓴다");
        }
        jdbc.update(RECORD, entry.id(), entry.actor(), entry.action(), entry.targetType(), entry.targetId(),
                json.writeValueAsString(entry.request()), result.name(), Timestamp.from(entry.createdAt()));
    }

    @Override
    public Optional<Row> lockForResolution(UUID id) {
        List<Row> rows = jdbc.query(LOCK, (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("action"),
                AuditResult.valueOf(rs.getString("result")), rs.getTimestamp("created_at").toInstant()), id);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<UUID> findResolution(UUID target) {
        return jdbc.queryForList(FIND_RESOLUTION, UUID.class, target).stream().findFirst();
    }
}
