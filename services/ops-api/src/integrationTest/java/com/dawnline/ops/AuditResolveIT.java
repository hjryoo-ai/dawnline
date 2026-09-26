package com.dawnline.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dawnline.common.Ids;
import com.dawnline.common.error.DomainException;
import com.dawnline.observability.MdcKeys;
import com.dawnline.ops.application.port.in.ResolveAuditUseCase;
import com.dawnline.ops.domain.AuditErrorCode;
import com.dawnline.ops.domain.AuditResolution;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 감사 해소는 칸이 아니라 행이다 — 실제 {@code audit_logs} 와 보안 필터를 지나서 (DESIGN.md §5.5 「감사 해소」, ADR-065).
 *
 * <p>픽스처는 되돌리지 않고 만들고 지운다(CLAUDE.md) — 대상 행을 이 테스트가 넣고, 끝나면 그 행과 이 테스트의 actor 가 남긴 행을
 * 지운다.
 */
@SpringBootTest(classes = OpsApplication.class)
@AutoConfigureMockMvc
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("AuditResolveIT — 감사 해소는 칸이 아니라 행이다")
class AuditResolveIT extends OpsIntegrationTestBase {

    private static final String ACTOR = "it-audit-resolve";
    private static final String OPERATOR = "Bearer " + OpsTokens.token("OPS_OPERATOR", ACTOR);
    private static final String VIEWER = "Bearer " + OpsTokens.token("OPS_VIEWER", ACTOR + "-viewer");
    private static final String REASON = "그 시각 dawnline_fulfillment 는 NOLOGIN 이었다(chaos-db)";

    /** 해소가 대상 행의 잠금에서 기다리는 백엔드 — 테스트 자신의 연결은 뺀다. */
    private static final String BLOCKED_ON_LOCK_SQL = """
            SELECT count(*) FROM pg_stat_activity
             WHERE datname = current_database() AND pid <> pg_backend_pid()
               AND wait_event_type = 'Lock' AND query ILIKE '%FROM audit_logs WHERE id =%FOR UPDATE%'
            """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ResolveAuditUseCase resolveAudit;

    /** 픽스처의 시각도 여기서 뽑는다 — 「5분 넘은 PENDING」이 이 시계와 견준다(CLAUDE.md). */
    @Autowired
    private Clock clock;

    private final List<UUID> targets = new ArrayList<>();

    @AfterEach
    void 만든_것을_지운다() {
        jdbc.update("DELETE FROM audit_logs WHERE actor LIKE ?", ACTOR + "%");
        for (UUID target : targets) {
            jdbc.update("DELETE FROM audit_logs WHERE id = ? OR target_id = ?", target, target);
        }
        targets.clear();
    }

    @Test
    void UNKNOWN_을_닫으면_해소_행이_더해지고_대상은_그대로다() throws Exception {
        UUID target = auditRow("UNKNOWN", clock.instant().minus(Duration.ofHours(1)));

        String resolutionId = resolve(target, OPERATOR, "NOT_APPLIED", REASON)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetAuditId").value(target.toString()))
                .andExpect(jsonPath("$.resolution").value("NOT_APPLIED"))
                .andExpect(jsonPath("$.actor").value(ACTOR))
                .andReturn().getResponse().getHeader(MdcKeys.AUDIT_ID_HEADER);

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT action, target_type, target_id, actor, result, request ->> 'resolution' AS resolution,
                       request ->> 'reason' AS reason
                  FROM audit_logs WHERE id = ?""", UUID.fromString(resolutionId));
        assertThat(row).containsEntry("action", "RESOLVE_AUDIT").containsEntry("target_type", "AUDIT")
                .containsEntry("target_id", target).containsEntry("actor", ACTOR).containsEntry("result", "SUCCEEDED")
                .containsEntry("resolution", "NOT_APPLIED").containsEntry("reason", REASON);
        assertThat(resultOf(target)).as("대상 행은 고치지 않는다 — 「그때 모른다고 적었다」가 남는다").isEqualTo("UNKNOWN");
    }

    @Test
    void 두_번째_해소는_409_이고_앞의_해소_행을_가리키며_거절_행이_남는다() throws Exception {
        UUID target = auditRow("UNKNOWN", clock.instant().minus(Duration.ofHours(1)));
        String first = resolve(target, OPERATOR, "APPLIED", REASON).andExpect(status().isOk())
                .andReturn().getResponse().getHeader(MdcKeys.AUDIT_ID_HEADER);

        resolve(target, OPERATOR, "NOT_APPLIED", "다르게 본다")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("audit-already-resolved"))
                .andExpect(jsonPath("$.resolutionId").value(first));

        assertThat(jdbc.queryForList("SELECT result FROM audit_logs WHERE target_id = ? ORDER BY created_at, id",
                String.class, target)).containsExactly("SUCCEEDED", "REJECTED");
    }

    @Test
    void 해소할_수_없는_행과_없는_행도_거절_행으로_남는다() throws Exception {
        UUID settled = auditRow("SUCCEEDED", clock.instant().minus(Duration.ofHours(1)));
        UUID fresh = auditRow("PENDING", clock.instant().minus(Duration.ofMinutes(1)));
        UUID missing = Ids.newId();
        targets.add(missing);

        resolve(settled, OPERATOR, "APPLIED", REASON).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("audit-not-resolvable"))
                .andExpect(jsonPath("$.currentResult").value("SUCCEEDED"));
        resolve(fresh, OPERATOR, "APPLIED", REASON).andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentResult").value("PENDING"));
        resolve(missing, OPERATOR, "APPLIED", REASON).andExpect(status().isNotFound());

        for (UUID target : List.of(settled, fresh, missing)) {
            assertThat(jdbc.queryForList("SELECT result FROM audit_logs WHERE action = 'RESOLVE_AUDIT' AND target_id = ?",
                    String.class, target)).as("%s", target).containsExactly("REJECTED");
        }
    }

    @Test
    void 오래된_PENDING_은_닫힌다() throws Exception {
        UUID stale = auditRow("PENDING", clock.instant().minus(Duration.ofMinutes(6)));

        resolve(stale, OPERATOR, "APPLIED", REASON).andExpect(status().isOk());
    }

    @Test
    void 성립하지_않는_요청과_뷰어는_행을_남기지_않는다() throws Exception {
        UUID target = auditRow("UNKNOWN", clock.instant().minus(Duration.ofHours(1)));

        resolve(target, OPERATOR, "APPLIED", "  ").andExpect(status().isBadRequest());
        resolve(target, OPERATOR, "APPLIED", "x".repeat(ResolveAuditUseCase.MAX_REASON_LENGTH + 1))
                .andExpect(status().isBadRequest());
        resolve(target, OPERATOR, "MAYBE", REASON).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/audit/{id}/resolve", target).header("Authorization", OPERATOR)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + REASON + "\"}"))
                .andExpect(status().isBadRequest());
        resolve(target, VIEWER, "APPLIED", REASON).andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_id = ?", Long.class, target))
                .as("400 · 403 은 커맨드가 성립하지 않았다").isZero();
    }

    @Test
    void 동시에_온_둘_중_하나만_적힌다() throws Exception {
        // 인터리빙을 고정한다: 테스트의 연결이 대상 행을 잡고 → 둘 다 잠금에서 기다리는 것을 본 뒤(전제) → 놓는다.
        UUID target = auditRow("UNKNOWN", clock.instant().minus(Duration.ofHours(1)));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM audit_logs WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, target);
                lock.executeQuery().close();
            }
            List<CompletableFuture<String>> racers = List.of(
                    CompletableFuture.supplyAsync(() -> attempt(target, AuditResolution.APPLIED), pool),
                    CompletableFuture.supplyAsync(() -> attempt(target, AuditResolution.NOT_APPLIED), pool));

            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                    .until(() -> jdbc.queryForObject(BLOCKED_ON_LOCK_SQL, Long.class) == 2);
            holder.commit();

            List<String> outcomes = new ArrayList<>();
            for (CompletableFuture<String> racer : racers) {
                outcomes.add(racer.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsExactlyInAnyOrder("SUCCEEDED", AuditErrorCode.ALREADY_RESOLVED.code());
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForList("SELECT result FROM audit_logs WHERE target_id = ?", String.class, target))
                .containsExactlyInAnyOrder("SUCCEEDED", "REJECTED");
    }

    // --- 도우미 ---------------------------------------------------------------

    private String attempt(UUID target, AuditResolution resolution) {
        try {
            resolveAudit.resolve(ACTOR + "-race", target, resolution, REASON);
            return "SUCCEEDED";
        } catch (DomainException e) {
            return e.code();
        }
    }

    private ResultActions resolve(UUID target, String token, String resolution, String reason) throws Exception {
        return mvc.perform(post("/api/v1/audit/{id}/resolve", target)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resolution\":\"" + resolution + "\",\"reason\":\"" + reason + "\"}"));
    }

    /** 해소할 대상 — 위임 커맨드의 행 모양(조기 마감). */
    private UUID auditRow(String result, Instant createdAt) {
        UUID id = Ids.newId();
        jdbc.update("""
                INSERT INTO audit_logs (id, actor, action, target_type, target_id, request, result, created_at)
                VALUES (?, ?, 'CLOSE_WAVE', 'WAVE', ?, '{"reason":"it"}'::jsonb, ?, ?)""",
                id, ACTOR + "-target", Ids.newId(), result, Timestamp.from(createdAt));
        targets.add(id);
        return id;
    }

    private String resultOf(UUID id) {
        return jdbc.queryForObject("SELECT result FROM audit_logs WHERE id = ?", String.class, id);
    }
}
