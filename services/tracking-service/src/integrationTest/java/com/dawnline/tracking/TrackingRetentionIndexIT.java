package com.dawnline.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.tracking.adapter.out.persistence.JdbcTrackingRetention;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 보존 정리의 배송 삭제가 {@code ix_ship_updated} 를 타는지 — 불변규칙 11
 * ([측정](../../../../../../../docs/benchmarks/phase7-retention-indexes.md) §1).
 *
 * <p>인덱스 없이는 배치마다 표 전체를 읽는다 — 30일치에서 하루치 정리가 26초다. <strong>인덱스가 사라지거나 질의가
 * 그것을 못 쓰게 바뀌는 것</strong>이 여기서 잡혀야 하는 사건이다. 질의는 어댑터의 상수 그대로다.
 *
 * <p>계획은 둘 다 본다 — custom(값을 아는 계획)과 generic(값을 모르는 계획). 정리기는 같은 문장을 배치마다 다시
 * 부르고, 드라이버의 준비된 문장은 다섯 번째 실행부터 generic 계획으로 갈 수 있다. 측정에서 둘은 다른 추정을 냈다.
 *
 * <p>통계를 첫 어설션으로 말한다 — 통계가 없으면 플래너는 짐작하며 인덱스를 고르고, 그 계획은 아무것도 증명하지
 * 않는다(CLAUDE.md 불변규칙 11).
 */
@SpringBootTest
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("TrackingRetentionIndexIT — 보존 삭제의 계획")
class TrackingRetentionIndexIT extends TrackingIntegrationTestBase {

    /** 피크일 하루치 (§8.2) — 31일에 고르게 흩어 둔다. 하루치(1/31)가 만료다. */
    private static final int ROWS = 150_000;

    /** 이 클래스가 만든 행의 표시 — 자기 행만 지운다. */
    private static final String MARKER_ROUTE = "0b5e0000-0000-7000-8000-00000000c1d6";

    @DynamicPropertySource
    static void 공유_자원을_끈다(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
        registry.add("dawnline.tracking.partitions.enabled", () -> "false");
        registry.add("dawnline.tracking.retention.cleanup-initial-delay-ms", () -> "3600000");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void fill() {
        jdbc.update("""
                INSERT INTO shipments (order_id, route_id, stop_seq, status, planned_arrival, eta_at, promised_end,
                                       updated_at)
                SELECT gen_random_uuid(), ?::uuid, (g % 30000) + 1,
                       CASE WHEN g % 100 < 5 THEN 'FAILED' ELSE 'COMPLETED' END,
                       t, t, t + interval '2 hours', t
                  FROM (SELECT g, now() - (g % 31) * interval '1 day' - interval '1 hour' AS t
                          FROM generate_series(0, ?) g) s
                """, MARKER_ROUTE, ROWS - 1);
        jdbc.execute("ANALYZE shipments");
    }

    /** 행만 지우지 않고 통계까지 되돌린다 — 같은 DB 를 쓰는 다음 클래스의 계획을 이 테스트가 정하지 않게. */
    @AfterEach
    void wipe() {
        jdbc.update("DELETE FROM shipments WHERE route_id = ?::uuid", MARKER_ROUTE);
        jdbc.execute("ANALYZE shipments");
    }

    @Test
    void 종결_삭제와_상한_삭제가_인덱스를_탄다() {
        assertThat(jdbc.queryForObject("SELECT reltuples FROM pg_class WHERE relname = 'shipments'", Double.class))
                .as("통계가 있다 — 없으면 플래너는 짐작하고, 그 계획은 아무것도 증명하지 않는다")
                .isGreaterThanOrEqualTo((double) ROWS);

        for (String sql : List.of(JdbcTrackingRetention.DELETE_SETTLED_SHIPMENTS_SQL,
                JdbcTrackingRetention.DELETE_SHIPMENTS_CAP_SQL)) {
            assertThat(plan(sql, "force_custom_plan", "now() - interval '30 days'"))
                    .as("custom 계획 — 순차 스캔이면 배치마다 표 전체를 읽는다")
                    .contains("ix_ship_updated").doesNotContain("Seq Scan on shipments");
            assertThat(plan(sql, "force_generic_plan", "now() - interval '30 days'"))
                    .as("generic 계획 — 정리기의 배치 반복이 여기로 간다")
                    .contains("ix_ship_updated").doesNotContain("Seq Scan on shipments");
        }
    }

    /** 어댑터의 문장을 그대로 준비하고({@code ?} → {@code $n}) 계획만 본다 — 실행하지 않는다. */
    private String plan(String sql, String cacheMode, String threshold) {
        String prepared = sql.replaceFirst("\\?", "\\$1").replaceFirst("\\?", "\\$2");
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("SET LOCAL plan_cache_mode = " + cacheMode);
            jdbc.execute("PREPARE retention_probe(timestamptz, int) AS " + prepared);
            try {
                return String.join("\n", jdbc.queryForList(
                        "EXPLAIN EXECUTE retention_probe(" + threshold + ", 1000)", String.class));
            } finally {
                jdbc.execute("DEALLOCATE retention_probe");
                status.setRollbackOnly();
            }
        });
    }
}
