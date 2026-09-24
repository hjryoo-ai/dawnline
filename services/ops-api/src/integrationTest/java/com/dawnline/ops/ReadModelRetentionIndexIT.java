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
 * <p>삭제는 계획을 둘 다 본다 — custom 과 generic. 정리기는 같은 문장을 배치마다(하루 약 150번) 다시 부르고,
 * 드라이버의 준비된 문장은 다섯 번째 실행부터 generic 계획으로 갈 수 있다.
 *
 * <p><strong>걸린 행 셈은 custom 만 본다.</strong> 하루 한 번 부르는 문장이라 도는 것은 값을 아는 계획이다 — 서버는
 * 처음 다섯 번을 custom 으로 짜고, 그 뒤에도 generic 이 더 쌀 때만 바꾼다. 이 규모(15만 행)에서 generic 계획은
 * 「결과 없음」의 선택도에 따라 순차 스캔으로 갈 수 있고(CI 에서 한 번 그랬다 — 같은 DB 의 다른 행이 통계를 바꾼다),
 * 운영 크기(1,365만)에서는 generic 도 인덱스를 탔다(측정 §2.2). 여기서 generic 을 요구하면 이 검사는 운영이 아니라
 * 픽스처의 분포를 잰다.
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
                       -- 오늘치는 아직 결과가 없다 — 운영의 분포(측정 문서와 같다). 전부 결과가 있으면 「결과 없음」의
                       -- 선택도가 0 으로 잡혀 셈의 계획이 운영과 다른 질문에 답한다.
                       CASE WHEN g % 91 = 0 THEN NULL WHEN g % 20 = 0 THEN 'FAILED' ELSE 'COMPLETED' END,
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
        // 셈은 하루 한 번이라 custom 만 — 클래스 머리말.
        assertThat(plan(JdbcReadModelRetention.COUNT_STUCK_SQL, "force_custom_plan", "(timestamptz)",
                "now() - interval '90 days'"))
                .as("걸린 행 셈 — 인덱스 없이는 매일 표 전체를 병렬로 읽는다")
                .contains("ix_rmo_updated").doesNotContain("Seq Scan on rm_orders");
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
