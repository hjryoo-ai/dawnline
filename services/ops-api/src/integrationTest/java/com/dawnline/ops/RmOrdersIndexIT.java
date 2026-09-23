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
 * 재집계 질의가 {@code ix_rmo_route}·{@code ix_rmo_wave} 를 타는지 — 불변규칙 11
 * ([측정](../../../../../../../docs/benchmarks/phase6-rm-orders-aggregate-index.md)).
 *
 * <p>개수 칸은 이벤트마다 다시 센다(ADR-051 결정 4). 그 질의가 순차 스캔으로 떨어지면 1일치에서도
 * 배송 한 건에 9 ms 이고 피크에서 따라갈 수 없다. <strong>인덱스가 사라지거나 질의가 그것을 못 쓰게
 * 바뀌는 것</strong>이 여기서 잡혀야 하는 사건이다.
 *
 * <p>통계를 첫 어설션으로 말한다 — 통계가 없으면 플래너는 짐작하며 인덱스를 고르고, 그 계획은
 * 아무것도 증명하지 않는다(CLAUDE.md 불변규칙 11).
 */
@SpringBootTest(classes = OpsApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("RmOrdersIndexIT — 재집계 질의의 계획")
class RmOrdersIndexIT extends OpsIntegrationTestBase {

    /** 피크일 하루치 (§8.2). */
    private static final int ROWS = 150_000;

    /** 이 클래스가 만든 행의 표시 — 자기 행만 지운다(픽스처는 만들고 지운다). */
    private static final String MARKER = "0b5e0000-0000-7000-8000-00000000c1d5";

    @DynamicPropertySource
    static void noBroker(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
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
    void 두_재집계가_인덱스를_탄다() {
        // 라우트당 210 · 웨이브당 1,500 (측정 문서와 같은 분포).
        jdbc.update("""
                INSERT INTO rm_orders (order_id, customer_id, order_status, delivery_outcome, wave_id, route_id)
                SELECT gen_random_uuid(), ?::uuid, 'DISPATCHED',
                       CASE WHEN g % 20 = 0 THEN 'FAILED' ELSE 'COMPLETED' END,
                       ('00000000-0000-0000-0001-' || lpad(to_hex(g / 1500), 12, '0'))::uuid,
                       ('00000000-0000-0000-0002-' || lpad(to_hex(g / 210), 12, '0'))::uuid
                  FROM generate_series(0, ?) g
                """, MARKER, ROWS - 1);
        jdbc.execute("ANALYZE rm_orders");

        assertThat(jdbc.queryForObject("SELECT reltuples FROM pg_class WHERE relname = 'rm_orders'", Double.class))
                .as("통계가 있다 — 없으면 플래너는 짐작하고, 그 계획은 아무것도 증명하지 않는다")
                .isGreaterThanOrEqualTo((double) ROWS);

        String route = explain("SELECT count(*) FROM rm_orders o WHERE o.route_id = '00000000-0000-0000-0002-000000000100'"
                + " AND o.delivery_outcome = 'COMPLETED'");
        String wave = explain("SELECT count(*) FROM rm_orders o WHERE o.wave_id = '00000000-0000-0000-0001-000000000010'");

        assertThat(route).contains("ix_rmo_route").as("순차 스캔이면 배송마다 표 전체를 읽는다")
                .doesNotContain("Seq Scan on rm_orders");
        assertThat(wave).contains("ix_rmo_wave").doesNotContain("Seq Scan on rm_orders");
    }

    private String explain(String query) {
        List<String> lines = jdbc.queryForList("EXPLAIN " + query, String.class);
        return String.join("\n", lines);
    }
}
