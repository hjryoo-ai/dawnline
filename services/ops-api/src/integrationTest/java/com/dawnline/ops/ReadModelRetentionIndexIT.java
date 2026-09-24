package com.dawnline.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.ops.adapter.out.persistence.JdbcReadModelRetention;
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
 * 읽기 모델 보존의 주문 삭제와 셈이 {@code ix_rmo_updated} 를 타는지 — 불변규칙 11
 * ([측정](../../../../../../../docs/benchmarks/phase7-retention-indexes.md) §2).
 *
 * <p>인덱스 없이는 배치마다 표 전체를 읽는다 — 90일치에서 하루치 정리가 약 76초다. <strong>인덱스가 사라지거나 질의가
 * 그것을 못 쓰게 바뀌는 것</strong>이 여기서 잡혀야 하는 사건이다. 질의는 어댑터의 상수 그대로다.
 *
 * <p>계획은 둘 다 본다 — custom 과 generic. 정리기는 같은 문장을 배치마다 다시 부르고, 드라이버의 준비된 문장은
 * 다섯 번째 실행부터 generic 계획으로 갈 수 있다.
 *
 * <p>통계를 첫 어설션으로 말한다 — 통계가 없으면 플래너는 짐작하며 인덱스를 고른다(CLAUDE.md 불변규칙 11).
 */
@SpringBootTest(classes = OpsApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("ReadModelRetentionIndexIT — 보존 삭제와 셈의 계획")
class ReadModelRetentionIndexIT extends OpsIntegrationTestBase {

    /** 피크일 하루치 (§8.2) — 91일에 고르게 흩어 둔다. 하루치(1/91)가 만료다. */
    private static final int ROWS = 150_000;

    /** 이 클래스가 만든 행의 표시 — 자기 행만 지운다. */
    private static final String MARKER = "0b5e0000-0000-7000-8000-00000000c1d7";

    @DynamicPropertySource
    static void 공유_자원을_끈다(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("dawnline.ops.kpi.on-time-initial-delay-ms", () -> "3600000");
        registry.add("dawnline.ops.retention.cleanup-initial-delay-ms", () -> "3600000");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void fill() {
        jdbc.update("""
                INSERT INTO rm_orders (order_id, customer_id, order_status, delivery_outcome, updated_at)
                SELECT gen_random_uuid(), ?::uuid, 'DISPATCHED',
                       CASE WHEN g % 20 = 0 THEN 'FAILED' ELSE 'COMPLETED' END,
                       now() - (g % 91) * interval '1 day' - interval '1 hour'
                  FROM generate_series(0, ?) g
                """, MARKER, ROWS - 1);
        jdbc.execute("ANALYZE rm_orders");
    }

    /** 행만 지우지 않고 통계까지 되돌린다 — 같은 DB 를 쓰는 다음 클래스의 계획을 이 테스트가 정하지 않게. */
    @AfterEach
    void wipe() {
        jdbc.update("DELETE FROM rm_orders WHERE customer_id = ?::uuid", MARKER);
        jdbc.execute("ANALYZE rm_orders");
    }

    @Test
    void 종결_삭제와_상한_삭제와_걸린_행_셈이_인덱스를_탄다() {
        assertThat(jdbc.queryForObject("SELECT reltuples FROM pg_class WHERE relname = 'rm_orders'", Double.class))
                .as("통계가 있다 — 없으면 플래너는 짐작하고, 그 계획은 아무것도 증명하지 않는다")
                .isGreaterThanOrEqualTo((double) ROWS);

        for (String sql : List.of(JdbcReadModelRetention.DELETE_SETTLED_ORDERS_SQL,
                JdbcReadModelRetention.DELETE_ORDERS_CAP_SQL)) {
            for (String mode : List.of("force_custom_plan", "force_generic_plan")) {
                assertThat(plan(sql, mode, "(timestamptz, int)", "now() - interval '90 days', 1000"))
                        .as("%s — 순차 스캔이면 배치마다 표 전체를 읽는다", mode)
                        .contains("ix_rmo_updated").doesNotContain("Seq Scan on rm_orders");
            }
        }
        for (String mode : List.of("force_custom_plan", "force_generic_plan")) {
            assertThat(plan(JdbcReadModelRetention.COUNT_STUCK_SQL, mode, "(timestamptz)", "now() - interval '90 days'"))
                    .as("%s — 걸린 행 셈", mode)
                    .contains("ix_rmo_updated").doesNotContain("Seq Scan on rm_orders");
        }
    }

    /** 어댑터의 문장을 그대로 준비하고({@code ?} → {@code $n}) 계획만 본다 — 실행하지 않는다. */
    private String plan(String sql, String cacheMode, String types, String arguments) {
        String prepared = sql.replaceFirst("\\?", "\\$1").replaceFirst("\\?", "\\$2");
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("SET LOCAL plan_cache_mode = " + cacheMode);
            jdbc.execute("PREPARE retention_probe" + types + " AS " + prepared);
            try {
                return String.join("\n", jdbc.queryForList(
                        "EXPLAIN EXECUTE retention_probe(" + arguments + ")", String.class));
            } finally {
                jdbc.execute("DEALLOCATE retention_probe");
                status.setRollbackOnly();
            }
        });
    }
}
