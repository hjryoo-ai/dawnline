package com.dawnline.ops.adapter.out.persistence;

import com.dawnline.ops.application.port.out.AuditLog;
import com.dawnline.ops.domain.AuditResult;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code audit_logs} (V1, DESIGN.md §5.5).
 *
 * <p>두 문장 모두 호출자의 트랜잭션 없이 돈다 — {@link JdbcTemplate} 는 트랜잭션 밖에서 자동 커밋이다.
 * {@link AuditLog} 가 요구하는 「{@code PENDING} 이 위임 전에 커밋된다」가 그것으로 성립한다.
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
}
