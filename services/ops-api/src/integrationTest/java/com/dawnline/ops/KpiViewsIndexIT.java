package com.dawnline.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * KPI 뷰의 버킷 술어가 인덱스의 식과 만나는지 — 불변규칙 11
 * ([측정](../../../../../../../docs/benchmarks/phase6-kpi-hourly-views-index.md)).
 *
 * <h2>이름이 아니라 식을 본다</h2>
 * 뷰의 {@code bucket_hour} 는 {@code date_trunc('hour', …, 'UTC')} 이고 인덱스는 그 식 그대로다. 둘이
 * 글자 하나라도 어긋나면(예: {@code COALESCE(failed_at, delivered_at)}) 플래너는 <em>조용히</em> 다른
 * 길로 간다 — 측정에서 그 음성 표본은 순차 스캔이 아니라 <strong>다른 인덱스의 캠프 접두</strong>만 타고
 * 43만 행을 걸렀다(peak 30일, 158 ms). 인덱스 이름만 보면 그것을 놓치므로 {@code Index Cond} 에 버킷
 * 식이 있는지를 본다.
 *
 * <h2>규모는 3일치다</h2>
 * 1일치에서는 접수 축 질의가 배송 축 인덱스의 캠프 접두를 빌려 쓴다 — 표 전체가 하루라 버킷 범위가
 * 걸러 주는 것이 캠프 하나와 같기 때문이다. 3일치부터 각 질의가 자기 인덱스를 고른다(측정 문서의
 * 규모별 표). 게이지 질의(전 캠프 24 버킷)는 이 규모에서 순차 스캔이 맞다 — 표의 3분의 1 이다.
 * 게이지는 같은 뷰를 읽으므로 식의 대조는 여기서 끝난다.
 */
@SpringBootTest(classes = OpsApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("KpiViewsIndexIT — 뷰의 버킷 식과 인덱스의 식")
class KpiViewsIndexIT extends OpsIntegrationTestBase {

    /** 피크일 3일치 (§8.2). */
    private static final int ROWS = 450_000;

    /** 이 클래스가 만든 행의 표시 — 자기 행만 지운다. */
    private static final String MARKER = "0b5e0000-0000-7000-8000-00000000c0e2";

    /** 캠프 10 중 하나 — 아래 적재의 {@code g % 10 = 3}. */
    private static final String CAMP = "'00000000-0000-0000-0003-000000000003'";

    /** 셋째 날 24 버킷 — 다른 IT 의 행이 없는 시각이고 시계와 비교되지 않는다. */
    private static final String DAY = "bucket_hour >= '2031-05-03T00:00:00Z' AND bucket_hour < '2031-05-04T00:00:00Z'";

    @DynamicPropertySource
    static void noBroker(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("dawnline.ops.kpi.on-time-initial-delay-ms", () -> "3600000");
    }

    @Autowired
    private JdbcTemplate jdbc;

    /** 행만 지우지 않고 통계까지 되돌린다 — 같은 DB 를 쓰는 다음 클래스의 계획을 이 테스트가 정하지 않게. */
    @AfterEach
    void wipe() {
        jdbc.update("DELETE FROM rm_orders WHERE customer_id = ?::uuid", MARKER);
        jdbc.execute("ANALYZE rm_orders");
    }

    @Test
    void 두_뷰의_버킷_술어가_인덱스의_식으로_내려간다() {
        // 측정 문서와 같은 분포: 캠프 10, 실패 5%, 배차 불가 3%(캠프·결과 없음), 개정 2%.
        jdbc.update("""
                INSERT INTO rm_orders (order_id, customer_id, order_status, delivery_outcome, camp_id,
                                       promised_end_original, promised_end_revised, delivered_at, failed_at, placed_at)
                SELECT gen_random_uuid(), ?::uuid,
                       CASE WHEN g % 33 = 0 THEN 'UNSERVICEABLE' ELSE 'DISPATCHED' END,
                       CASE WHEN g % 33 = 0 THEN NULL WHEN g % 20 = 0 THEN 'FAILED' ELSE 'COMPLETED' END,
                       CASE WHEN g % 33 = 0 THEN NULL
                            ELSE ('00000000-0000-0000-0003-' || lpad(to_hex(g % 10), 12, '0'))::uuid END,
                       p + interval '6 hours',
                       CASE WHEN g % 50 = 0 THEN p + interval '8 hours' ELSE p + interval '6 hours' END,
                       CASE WHEN g % 33 = 0 OR g % 20 = 0 THEN NULL
                            ELSE p + interval '5 hours' + (g % 7) * interval '20 minutes' END,
                       CASE WHEN g % 33 <> 0 AND g % 20 = 0 THEN p + interval '5 hours' END,
                       p
                  FROM (SELECT g, timestamptz '2031-05-01T00:00:00Z' + g * interval '1 day' / 150000 AS p
                          FROM generate_series(0, ?) g) s
                """, MARKER, ROWS - 1);
        jdbc.execute("ANALYZE rm_orders");

        assertThat(jdbc.queryForObject("SELECT reltuples FROM pg_class WHERE relname = 'rm_orders'", Double.class))
                .as("통계가 있다 — 없으면 플래너는 짐작하고, 그 계획은 아무것도 증명하지 않는다")
                .isGreaterThanOrEqualTo((double) ROWS);

        String delivery = explain("SELECT * FROM kpi_delivery_hourly WHERE camp_id = " + CAMP + " AND " + DAY);
        String intake = explain("SELECT * FROM kpi_intake_hourly WHERE camp_id = " + CAMP + " AND " + DAY);

        assertThat(indexCond(delivery, "ix_rmo_delivery_hour"))
                .as("배송 축 — 캠프 접두만 타면 캠프의 전 기간을 거른다\n%s", delivery)
                .contains("date_trunc('hour'::text, COALESCE(delivered_at, failed_at), 'UTC'::text)");
        assertThat(indexCond(intake, "ix_rmo_intake_hour"))
                .as("접수 축\n%s", intake)
                .contains("date_trunc('hour'::text, placed_at, 'UTC'::text)");
        assertThat(delivery + intake).doesNotContain("Seq Scan on rm_orders");
    }

    private String explain(String query) {
        return String.join("\n", jdbc.queryForList("EXPLAIN " + query, String.class));
    }

    /** {@code index} 를 쓰는 노드 바로 아래의 {@code Index Cond} 줄. 그 인덱스를 안 쓰면 빈 문자열. */
    private static String indexCond(String plan, String index) {
        List<String> lines = plan.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("using " + index) || lines.get(i).contains("on " + index)) {
                for (int j = i + 1; j < lines.size(); j++) {
                    if (lines.get(j).contains("Index Cond:")) {
                        return lines.get(j);
                    }
                }
            }
        }
        return "";
    }
}
