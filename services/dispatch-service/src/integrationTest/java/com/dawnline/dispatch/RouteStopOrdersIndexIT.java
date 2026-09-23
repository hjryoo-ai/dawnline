package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code ix_rso_order} 가 값을 하는지 본다 — 불변규칙 11
 * ([측정](../../../../../../../docs/benchmarks/phase5-route-stop-orders-order-lookup.md)).
 *
 * <h2>이 클래스가 지키는 것</h2>
 * {@code delivery.status} 는 stop 방문마다 주문으로 stop 을 찾는다(ADR-047 결정 2). 그 조회가
 * 순차 스캔으로 떨어지면 피크에서 한 세션으로 따라갈 수 없다 — 30일치 테이블에서 한 건에
 * 5.9 ms 였다. <strong>인덱스가 사라지거나 질의가 그것을 못 쓰게 바뀌는 것</strong>이 여기서
 * 잡혀야 하는 사건이다.
 *
 * <h2>통계를 첫 어설션으로 말한다</h2>
 * 통계가 없으면 {@code reltuples = -1} 이고 플래너는 기본 추정치로 <strong>짐작하며 인덱스를
 * 고른다</strong> — 50행짜리 테이블에서도 그렇다. 그때의 「인덱스를 탔다」는 아무것도 증명하지
 * 않는다(Phase 4 의 {@code DispatchPersistenceIT} 가 2026-09-07 까지 그랬다).
 */
@SpringBootTest(classes = DispatchApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("RouteStopOrdersIndexIT — 주문으로 stop 을 찾는 계획")
class RouteStopOrdersIndexIT extends DispatchIntegrationTestBase {

    /** 릴레이를 끈다 — 이 클래스는 발행을 보지 않는다 (ADR-027 후속 정정). */
    @DynamicPropertySource
    static void relayOff(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    /** 피크일 한 캠프치에 가깝게 — stop 8,520 · 주문 행 14,910 (§8.2, 팬아웃 측정). */
    private static final int ROUTES = 71;
    private static final int STOPS_PER_ROUTE = 120;

    /** {@code JdbcRouteMutations.lastSettledStop} 의 질의 그대로 (§6.8, ADR-048). */
    private static final String ANCHOR = """
            SELECT seq, planned_arrival, actual_at FROM route_stops
             WHERE route_id = '%s' AND actual_at IS NOT NULL
             ORDER BY seq DESC
             LIMIT 1
            """;

    /** {@code JdbcRouteMutations.findAssignedStop} 의 질의 그대로. */
    private static final String LOOKUP = """
            SELECT s.route_id, s.id, s.seq, s.status
              FROM route_stops s
              JOIN route_stop_orders o ON o.stop_id = s.id
             WHERE o.order_id = '%s'
             ORDER BY s.id DESC
             LIMIT 1
            """;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * 행만 지우지 않고 <strong>통계까지 되돌린다.</strong>
     *
     * <p>{@code pg_class.reltuples} 가 남으면 이 컨테이너의 dispatch DB 를 함께 쓰는 다음
     * 클래스의 계획을 <em>이 테스트가</em> 정하게 된다. 여기서 만들어 낸 통계는 여기서 치운다.
     */
    @AfterEach
    void wipe() {
        tx().executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM route_stop_orders").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_stops").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM routes").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_plans").executeUpdate();
        });
        analyze();
    }

    @Test
    void 주문으로_stop_을_찾는_질의가_ix_rso_order_를_탄다() {
        seed();
        analyze();

        assertThat(reltuples("route_stop_orders"))
                .as("통계가 있다 — 없으면 플래너는 짐작하고, 그 계획은 아무것도 증명하지 않는다")
                .isGreaterThan(0.0d);
        assertThat(count("route_stop_orders"))
                .as("운영에 가까운 크기여야 한다 — 작은 테이블에서 옳은 계획은 순차 스캔이다")
                .isGreaterThan(10_000L);

        String plan = explain(probeOrderId());

        assertThat(plan).contains("ix_rso_order");
        assertThat(plan).as("순차 스캔이면 stop 방문마다 테이블 전체를 읽는다")
                .doesNotContain("Seq Scan on route_stop_orders");
    }

    @Test
    void 마지막으로_닿은_stop_을_찾는_질의는_새_인덱스가_필요하지_않다() {
        // 불변규칙 11 은 «넣지 않기로 한 판단도 행 수와 함께» 기록하라고 한다. 이 클래스에
        // 두는 이유는 seed 를 함께 쓰기 때문이다 — 같은 크기에서 두 질의를 나란히 본다.
        // 재계획은 라우트마다 이 조회를 한 번씩 한다(원 라우트 + 후보 전부, §6.8 2단계).
        seed();
        markSettled();
        analyze();

        assertThat(reltuples("route_stops"))
                .as("통계가 있다 — 없으면 플래너는 짐작한다").isGreaterThan(0.0d);
        assertThat(count("route_stops"))
                .as("피크일 한 캠프치에 가깝다 (§8.2)").isGreaterThan(8_000L);

        String plan = explainAnchor(probeRouteId());

        assertThat(plan)
                .as("UNIQUE (route_id, seq) 를 역순으로 한 건 읽으면 된다 — 부분 인덱스를 더하면 "
                        + "쓰기마다 그것을 갱신하는 비용만 남는다")
                .contains("route_stops_route_id_seq_key");
        assertThat(plan).as("순차 스캔이면 후보 라우트 수만큼 테이블 전체를 읽는다")
                .doesNotContain("Seq Scan on route_stops");
    }

    // --- 픽스처 --------------------------------------------------------------

    private void seed() {
        UUID planId = UUID.randomUUID();
        tx().executeWithoutResult(status -> {
            entityManager.createNativeQuery("""
                    INSERT INTO route_plans (id, wave_id, camp_id, status)
                    VALUES (?, ?, ?, 'PUBLISHED')
                    """).setParameter(1, planId).setParameter(2, UUID.randomUUID())
                    .setParameter(3, UUID.randomUUID()).executeUpdate();
            entityManager.createNativeQuery("""
                    INSERT INTO routes (id, plan_id, vehicle_id, seq_no, status, revision,
                                        stop_count, distance_m, duration_s, cost_krw)
                    SELECT gen_random_uuid(), ?, (SELECT id FROM vehicles LIMIT 1),
                           g, 'DISPATCHED', 1, ?, 0, 0, 0
                      FROM generate_series(1, ?) g
                    """).setParameter(1, planId).setParameter(2, STOPS_PER_ROUTE)
                    .setParameter(3, ROUTES).executeUpdate();
            entityManager.createNativeQuery("""
                    INSERT INTO route_stops (id, route_id, seq, lat, lng, planned_arrival,
                                             planned_departure, service_s, status)
                    SELECT gen_random_uuid(), r.id, s.seq, 37.5, 127.0, now(), now(), 60, 'PLANNED'
                      FROM routes r CROSS JOIN generate_series(1, ?) AS s(seq)
                     WHERE r.plan_id = ?
                    """).setParameter(1, STOPS_PER_ROUTE).setParameter(2, planId).executeUpdate();
            // stop 당 주문 1.75개 — 측정한 피크 배율 1.7834 에 가깝다 (seq % 4 <> 0 이 75%).
            entityManager.createNativeQuery("""
                    INSERT INTO route_stop_orders (stop_id, order_id)
                    SELECT s.id, gen_random_uuid() FROM route_stops s
                    """).executeUpdate();
            entityManager.createNativeQuery("""
                    INSERT INTO route_stop_orders (stop_id, order_id)
                    SELECT s.id, gen_random_uuid() FROM route_stops s WHERE s.seq % 4 <> 0
                    """).executeUpdate();
        });
    }

    /** 기사가 라우트의 4분의 1쯤 간 상태 — 닿은 stop 이 있고 남은 stop 이 더 많다. */
    private void markSettled() {
        tx().executeWithoutResult(status -> entityManager.createNativeQuery("""
                UPDATE route_stops SET actual_at = planned_arrival, status = 'COMPLETED'
                 WHERE seq <= 30
                """).executeUpdate());
    }

    private UUID probeRouteId() {
        return tx().execute(status -> (UUID) entityManager
                .createNativeQuery("SELECT id FROM routes LIMIT 1").getSingleResult());
    }

    @SuppressWarnings("unchecked")
    private String explainAnchor(UUID routeId) {
        List<String> lines = tx().execute(status -> entityManager
                .createNativeQuery("EXPLAIN (ANALYZE, BUFFERS) " + ANCHOR.formatted(routeId))
                .getResultList());
        String plan = String.join("\n", lines);
        // 측정값을 문서로 옮길 수 있게 남긴다 (docs/benchmarks/phase5-replan-anchor-lookup.md).
        System.out.println("[ANCHOR PLAN]\n" + plan);
        return plan;
    }

    private UUID probeOrderId() {
        return tx().execute(status -> (UUID) entityManager
                .createNativeQuery("SELECT order_id FROM route_stop_orders LIMIT 1")
                .getSingleResult());
    }

    @SuppressWarnings("unchecked")
    private String explain(UUID orderId) {
        List<String> lines = tx().execute(status -> entityManager
                .createNativeQuery("EXPLAIN " + LOOKUP.formatted(orderId)).getResultList());
        return String.join("\n", lines);
    }

    private void analyze() {
        tx().executeWithoutResult(status -> entityManager
                .createNativeQuery("ANALYZE routes, route_stops, route_stop_orders")
                .executeUpdate());
    }

    private long count(String table) {
        return tx().execute(status -> ((Number) entityManager
                .createNativeQuery("SELECT count(*) FROM " + table).getSingleResult()).longValue());
    }

    private double reltuples(String table) {
        return tx().execute(status -> ((Number) entityManager.createNativeQuery(
                        "SELECT reltuples FROM pg_class WHERE relname = ?")
                .setParameter(1, table).getSingleResult()).doubleValue());
    }
}
