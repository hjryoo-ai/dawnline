package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.common.error.DomainException;
import com.dawnline.dispatch.adapter.out.persistence.JdbcDispatchRetention;
import com.dawnline.dispatch.application.DispatchRetentionCleaner;
import com.dawnline.dispatch.application.port.in.PlanView;
import com.dawnline.dispatch.application.port.in.ReassignStopUseCase;
import com.dawnline.dispatch.application.port.in.RouteView;
import com.dawnline.dispatch.application.port.in.RunPlanCommand;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.application.port.out.DispatchRetention.PlanRef;
import com.dawnline.dispatch.application.port.out.DispatchRetention.PlanRows;
import com.dawnline.dispatch.application.port.out.PlanQueries;
import com.dawnline.dispatch.application.port.out.RouteMutations;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.dispatch.domain.DispatchErrorCode;
import com.dawnline.messaging.retention.RetentionAges;
import com.dawnline.observability.DawnlineMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * dispatch 보존 정리 ([ADR-059](docs/adr/ADR-059-dispatch-retention-is-per-plan.md)) — 실제 PostgreSQL 18.
 *
 * <p>단위 테스트({@code DispatchRetentionCleanerTest})는 단계의 순서와 트랜잭션 단위만 본다. 여기서 보는 것은
 * <strong>SQL 이 실제로 무엇을 지우고 무엇을 세는가</strong>와, 후보가 사라진 라우트를 고치는 경로가
 * <strong>조용히 stop 을 빼지 않는가</strong>다.
 *
 * <ol>
 *   <li>종결은 「계획이 발행·실패 ∧ 그 계획의 모든 stop 이 종결 목록 안」이다. 모르는 stop 상태는 종결이 아니다.</li>
 *   <li>걸린 계획 셈은 종결 고르기의 여집합이다 — <strong>지우기 전에</strong> 같은 스냅숏에서 센다.</li>
 *   <li>계열 삭제는 여섯 표에 아무것도 남기지 않는다. 후보에는 계획으로의 FK 가 없어 빠뜨려도 FK 가 말해 주지 않는다.</li>
 *   <li>계열 삭제 여섯 문장은 custom · generic 두 계획에서 기존 인덱스를 탄다 — 통계가 있다를 먼저 말한다.</li>
 *   <li>후보가 없으면 재배정 · 개정 스냅숏 · 취소는 409 {@code candidates-expired} 로 실패한다.</li>
 * </ol>
 *
 * <p>정리기는 컨텍스트의 빈을 쓰지 않고 여기서 만든다 — 임계를 고정된 {@link #NOW} 에서 재야 픽스처의 나이가
 * 정해진다. 픽스처의 시각도 같은 {@link #NOW} 에서 뽑는다(CLAUDE.md 「시각 리터럴을 쓰지 않는다」).
 */
@SpringBootTest(classes = DispatchApplication.class)
@Import(PlanningClock.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("DispatchRetentionIT — 계획 단위 보존과 candidates-expired")
class DispatchRetentionIT extends DispatchIntegrationTestBase {

    private static final Instant NOW = PlanningClock.PLAN_AT.truncatedTo(ChronoUnit.MICROS);
    private static final Duration SHORT = Duration.ofDays(30);
    private static final Duration PLANS = Duration.ofDays(90);
    private static final Duration CAP = Duration.ofDays(365);

    /** 시드의 첫 캠프(서울 북부)와 그 캠프의 첫 차량. */
    private static final UUID CAMP_ID = UUID.fromString("01a06edd-6c00-7000-8001-000000000001");
    private static final GeoPoint CAMP = GeoPoint.of(37.640000, 127.030000);
    private static final UUID VEHICLE_ID = UUID.fromString("01a06edd-6c00-7000-8004-000000000001");

    /** 여섯 표 — 자식부터. 지우는 순서이기도 하다. */
    private static final List<String> TABLES = List.of("route_stop_orders", "route_stops", "routes",
            "plan_explanations", "dispatch_candidates", "route_plans");

    /** 릴레이를 끈다 — 이 클래스는 발행을 보지 않는다(재배정은 outbox 행까지만 쓴다). */
    @DynamicPropertySource
    static void relayOff(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RunPlanUseCase runPlan;

    @Autowired
    private DispatchCandidateRepository candidates;

    @Autowired
    private PlanQueries planQueries;

    @Autowired
    private ReassignStopUseCase reassign;

    @Autowired
    private RouteMutations routeMutations;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private JdbcDispatchRetention retention() {
        return new JdbcDispatchRetention(jdbc);
    }

    /** 붙잡아 두지 않는다 — 게이지의 상태는 등록 헬퍼가 강한 참조로 잡는다(ADR-060, 7-0c 의 필드 우회를 걷어냈다). */
    private DispatchRetentionCleaner cleaner() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new DispatchRetentionCleaner(retention(), transactionManager, clock, SHORT, SHORT, PLANS, CAP,
                200, 1000, 10, new RetentionAges(new SimpleMeterRegistry(), clock), meters);
    }

    /**
     * 여섯 표를 비운다 — 이 컨테이너의 dispatch DB 를 함께 쓰는 다른 클래스가 남긴 행도 지운다. 정리는 표 전체를
     * 보므로 남의 행이 셈을 바꾼다.
     */
    @BeforeEach
    void clean() {
        tx().executeWithoutResult(status -> {
            TABLES.forEach(table -> jdbc.update("DELETE FROM " + table));
            jdbc.update("DELETE FROM outbox_events");
        });
    }

    /**
     * 통계까지 되돌린다 — 계획 검사가 채운 표의 {@code reltuples} 가 남으면 다음 클래스의 계획을 이 클래스가
     * 정한다({@code RouteStopOrdersIndexIT} 와 같은 이유).
     */
    @AfterEach
    void wipe() {
        clean();
        TABLES.forEach(table -> jdbc.execute("ANALYZE " + table));
    }

    // ------------------------------------------------------------------ 30일 단계 — 종결의 경계

    @Test
    void 종결_계획의_설명과_후보를_30일에_지우고_계열은_남긴다() {
        Fixture old = plan("PUBLISHED", Duration.ofDays(31), "COMPLETED", "FAILED", "CANCELLED");
        Fixture young = plan("PUBLISHED", Duration.ofDays(29), "COMPLETED", "COMPLETED");

        DispatchRetentionCleaner.Deleted deleted = cleaner().deleteExpired();

        assertThat(deleted.explanations()).isEqualTo(3);
        assertThat(deleted.candidates()).isEqualTo(3);
        assertThat(rows(old)).as("30일 단계는 계열을 건드리지 않는다").isEqualTo(Map.of(
                "route_stop_orders", 3L, "route_stops", 3L, "routes", 1L,
                "plan_explanations", 0L, "dispatch_candidates", 0L, "route_plans", 1L));
        assertThat(rows(young)).as("29일은 남는다").containsEntry("plan_explanations", 2L)
                .containsEntry("dispatch_candidates", 2L);
        assertThat(deleted.stuckPlans()).isZero();
    }

    @ParameterizedTest(name = "stop 하나가 {0}")
    @ValueSource(strings = {"PLANNED", "ARRIVED", "LOST"})
    void 끝나지_않은_stop_이_하나라도_있으면_후보와_설명을_남기고_센다(String open) {
        // LOST 는 스키마가 막지 않는 모르는 값이다(CHECK 없음, §4.7). 종결은 종결 목록으로 적었으므로 모름은
        // 종결이 아니다 — 비종결 목록(PLANNED · ARRIVED)으로 적었다면 LOST 가 종결로 읽혀 지워진다.
        Fixture stuck = plan("PUBLISHED", Duration.ofDays(31), "COMPLETED", open, "COMPLETED");

        DispatchRetentionCleaner.Deleted deleted = cleaner().deleteExpired();

        assertThat(rows(stuck)).containsEntry("plan_explanations", 3L).containsEntry("dispatch_candidates", 3L);
        assertThat(deleted.stuckPlans()).isEqualTo(1);
        assertThat(meters.get(DawnlineMetrics.ROUTE_PLANS_STUCK.meterName()).gauge().value()).isEqualTo(1.0);
    }

    @Test
    void 실패한_계획은_보존에서_종결이다() {
        // 도메인의 PlanStatus.isTerminal() 은 PUBLISHED 만 참이지만(운영자 재실행), 30일 뒤의 재실행에는 답할
        // 후보가 없다 — fulfillment 도 PLAN_FAILED 웨이브의 주문을 30일에 놓는다(ADR-059 결정 2).
        Fixture failed = plan("FAILED", Duration.ofDays(31));

        DispatchRetentionCleaner.Deleted deleted = cleaner().deleteExpired();

        assertThat(rows(failed)).containsEntry("dispatch_candidates", 0L).containsEntry("plan_explanations", 0L)
                .containsEntry("route_plans", 1L);
        assertThat(deleted.stuckPlans()).isZero();
    }

    @Test
    void 계획_상태가_끝나지_않았으면_걸린_것이다() {
        // 첫 실행 중인 계획은 finished_at 이 없다 — 셈은 started_at 으로 나이를 잰다(ADR-059 결정 1).
        Fixture planning = plan("PLANNING", Duration.ofDays(31));

        DispatchRetentionCleaner.Deleted deleted = cleaner().deleteExpired();

        assertThat(rows(planning)).containsEntry("dispatch_candidates", 1L)
                .containsEntry("route_plans", 1L);
        assertThat(deleted.stuckPlans()).isEqualTo(1);
    }

    @Test
    void 걸린_셈과_종결_고르기는_지우기_전에_같은_스냅숏에서_여집합이다() {
        // 지운 뒤에 세면 넓어진 셈(종결 계획까지 센다)이 보이지 않는다 — 종결 계획은 이미 사라졌다(§13 축 10 변종).
        plan("PUBLISHED", Duration.ofDays(31), "COMPLETED");
        plan("PUBLISHED", Duration.ofDays(45), "COMPLETED", "CANCELLED");
        plan("FAILED", Duration.ofDays(60));
        plan("PUBLISHED", Duration.ofDays(95), "FAILED");
        plan("PUBLISHED", Duration.ofDays(31), "PLANNED");
        plan("PUBLISHED", Duration.ofDays(70), "COMPLETED", "ARRIVED");
        plan("PLANNING", Duration.ofDays(40));
        plan("PUBLISHED", Duration.ofDays(10), "PLANNED");
        Instant threshold = NOW.minus(SHORT);

        long stuck = tx().execute(status -> retention().countStuckPlansAgedBefore(threshold));
        List<PlanRef> settled = retention().settledPlansFinishedBefore(threshold, 1000);
        long aged = jdbc.queryForObject(
                "SELECT count(*) FROM route_plans WHERE COALESCE(finished_at, started_at) < ?", Long.class,
                Timestamp.from(threshold));

        assertThat(settled.size() + stuck).as("30일을 넘긴 계획은 종결이거나 걸렸다 — 둘 다이거나 둘 다 아닌 것은 없다")
                .isEqualTo(aged);
        // 합만 맞으면 한쪽이 넓고 다른 쪽이 좁아도 통과한다 — 각각의 값도 본다.
        assertThat(stuck).as("걸린 것: 31일 PLANNED · 70일 ARRIVED · 40일 PLANNING").isEqualTo(3);
        assertThat(settled).as("종결: 31일 · 45일 · 60일 FAILED · 95일").hasSize(4);
    }

    // ------------------------------------------------------------------ 90일 · 상한 — 계열 삭제

    @Test
    void 계열_삭제는_여섯_표를_자식부터_지우고_아무것도_남기지_않는다() {
        // 여섯 표가 전부 남은 계획 — 상한이 걸린 계획을 지울 때의 모양이다(ADR-059 결정 6 의 최악).
        Fixture full = plan("PUBLISHED", Duration.ofDays(91), "COMPLETED", "COMPLETED");
        Fixture neighbour = plan("PUBLISHED", Duration.ofDays(91), "COMPLETED");

        PlanRows deleted = tx().execute(status -> retention().deletePlan(full.ref()));

        assertThat(deleted).isEqualTo(new PlanRows(2, 2, 1, 2, 2, 1));
        // 후보는 계획으로의 FK 가 없다(웨이브 id 로만 잇는다) — 빠뜨려도 FK 가 알려 주지 않으므로 여기서 센다.
        assertThat(rows(full)).allSatisfy((table, count) -> assertThat(count).as(table).isZero());
        assertThat(rows(neighbour)).as("다른 계획은 그대로다").containsEntry("route_plans", 1L)
                .containsEntry("dispatch_candidates", 1L).containsEntry("route_stop_orders", 1L);
    }

    @Test
    void 종결_계획은_90일에_계열째_지운다() {
        Fixture old = plan("PUBLISHED", Duration.ofDays(91), "COMPLETED", "CANCELLED");
        Fixture young = plan("PUBLISHED", Duration.ofDays(89), "COMPLETED");

        DispatchRetentionCleaner.Deleted deleted = cleaner().deleteExpired();

        assertThat(deleted.plans()).isEqualTo(1);
        assertThat(rows(old)).allSatisfy((table, count) -> assertThat(count).as(table).isZero());
        assertThat(rows(young)).containsEntry("route_plans", 1L).containsEntry("route_stops", 1L);
    }

    @Test
    void 끝나지_않은_계획은_90일에도_남고_365일_상한에_계열째_지운다() {
        Fixture stuck = plan("PUBLISHED", Duration.ofDays(200), "PLANNED");
        Fixture capped = plan("PUBLISHED", Duration.ofDays(366), "ARRIVED");
        Fixture cappedPlanning = plan("PLANNING", Duration.ofDays(366));

        DispatchRetentionCleaner.Deleted deleted = cleaner().deleteExpired();

        assertThat(deleted.cappedPlans()).isEqualTo(2);
        assertThat(rows(capped)).allSatisfy((table, count) -> assertThat(count).as(table).isZero());
        assertThat(rows(cappedPlanning)).allSatisfy((table, count) -> assertThat(count).as(table).isZero());
        assertThat(rows(stuck)).as("상한 전에는 조사 대상이다").containsEntry("route_plans", 1L)
                .containsEntry("dispatch_candidates", 1L);
        assertThat(deleted.stuckPlans()).as("셈은 상한이 지운 뒤다").isEqualTo(1);
    }

    @Test
    void 계획_없는_웨이브의_후보만_365일에_지운다() {
        UUID orphanOld = orphanCandidate(Ids.newId(), Duration.ofDays(366));
        UUID orphanYoung = orphanCandidate(Ids.newId(), Duration.ofDays(364));
        // 계획이 있는 웨이브의 후보는 자기 나이가 상한을 넘어도 계획과 함께만 지운다(ADR-059 결정 5).
        Fixture stuck = plan("PUBLISHED", Duration.ofDays(100), "PLANNED");
        jdbc.update("UPDATE dispatch_candidates SET updated_at = ? WHERE wave_id = ?",
                Timestamp.from(NOW.minus(Duration.ofDays(400))), stuck.waveId());

        DispatchRetentionCleaner.Deleted deleted = cleaner().deleteExpired();

        assertThat(deleted.orphanCandidates()).isEqualTo(1);
        assertThat(candidateExists(orphanOld)).isFalse();
        assertThat(candidateExists(orphanYoung)).isTrue();
        assertThat(rows(stuck)).containsEntry("dispatch_candidates", 1L);
    }

    // ------------------------------------------------------------------ 계획 — 기존 인덱스

    @Test
    void 계열_삭제_여섯_문장은_두_계획_모두에서_기존_인덱스를_탄다() {
        fillForPlans();
        TABLES.forEach(table -> jdbc.execute("ANALYZE " + table));
        for (String table : TABLES) {
            assertThat(jdbc.queryForObject("SELECT reltuples FROM pg_class WHERE relname = ?", Float.class, table))
                    .as("%s — 통계가 있다. 없으면 플래너는 짐작하고, 그 계획은 아무것도 증명하지 않는다", table)
                    .isGreaterThan(1_000f);
        }
        PlanRef probe = jdbc.queryForObject("SELECT id, wave_id FROM route_plans ORDER BY id LIMIT 1",
                (rs, n) -> new PlanRef(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)));

        Map<String, List<String>> expected = Map.of(
                JdbcDispatchRetention.DELETE_STOP_ORDERS_SQL,
                List.of("ix_routes_plan", "route_stops_route_id_seq_key", "route_stop_orders_pkey"),
                JdbcDispatchRetention.DELETE_STOPS_SQL, List.of("ix_routes_plan", "route_stops_route_id_seq_key"),
                JdbcDispatchRetention.DELETE_ROUTES_SQL, List.of("ix_routes_plan"),
                JdbcDispatchRetention.DELETE_EXPLANATIONS_SQL, List.of("ix_expl_plan_order"),
                JdbcDispatchRetention.DELETE_CANDIDATES_SQL, List.of("ix_cand_wave"),
                JdbcDispatchRetention.DELETE_PLAN_SQL, List.of("route_plans_pkey"));

        for (String mode : List.of("force_custom_plan", "force_generic_plan")) {
            expected.forEach((sql, indexes) -> {
                UUID key = sql.equals(JdbcDispatchRetention.DELETE_CANDIDATES_SQL) ? probe.waveId() : probe.planId();
                String plan = explain(mode, sql, key);
                assertThat(plan).as("%s\n%s", mode, plan).contains(indexes);
                assertThat(plan).as("%s — 계획 하나를 지우는데 표 전체를 읽는다\n%s", mode, plan)
                        .doesNotContain("Seq Scan");
            });
        }
    }

    // ------------------------------------------------------------------ candidates-expired (ADR-059 결정 3)

    @Test
    void 후보가_전부_지워진_계획에서_재배정하면_409_candidates_expired() {
        // 전에는 moveOrder 의 IllegalStateException — 500 이었다.
        TwoRoutes routes = twoRoutes();
        jdbc.update("DELETE FROM dispatch_candidates WHERE wave_id = ?", routes.waveId());
        List<UUID> fromBefore = ordersOf(routes.fromRouteId());

        assertThatThrownBy(() -> reassign.reassign(routes.fromRouteId(), routes.orderId(), routes.toRouteId()))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(DispatchErrorCode.CANDIDATES_EXPIRED);
                    assertThat(e.status()).isEqualTo(409);
                });
        assertThat(ordersOf(routes.fromRouteId())).isEqualTo(fromBefore);
    }

    @Test
    void 후보_없이_재배정하면_출발_라우트의_stop_이_빠지지_않는다() {
        // 옮기는 주문의 후보는 있고 출발 라우트에 남는 주문들의 후보가 전부 없다. 내부 조인이던 때는 시각
        // 재계산(loadPositionedStops)이 빈 목록을 돌려줬고, 재배정은 그것을 「빈 라우트」로 읽어 출발 라우트를
        // 비웠다(clear — stop 과 주문 행을 지우고 200). 근거: 관측(재현됨) — 이 테스트의 음성 표본.
        TwoRoutes routes = twoRoutes();
        List<UUID> fromBefore = ordersOf(routes.fromRouteId());
        List<UUID> remaining = fromBefore.stream().filter(id -> !id.equals(routes.orderId())).toList();
        remaining.forEach(id -> jdbc.update("DELETE FROM dispatch_candidates WHERE order_id = ?", id));

        Throwable thrown = catchThrowable(
                () -> reassign.reassign(routes.fromRouteId(), routes.orderId(), routes.toRouteId()));

        // 라우트를 먼저 본다 — 결함의 모양은 예외가 없는 것이 아니라 stop 이 빠지는 것이다.
        assertThat(ordersOf(routes.fromRouteId())).as("출발 라우트의 주문이 그대로다 — 옮긴 것도 빠진 것도 없다")
                .isEqualTo(fromBefore);
        assertThat(thrown).isInstanceOfSatisfying(DomainException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(DispatchErrorCode.CANDIDATES_EXPIRED);
            assertThat(e.status()).isEqualTo(409);
        });
    }

    @Test
    void 한_주문짜리_stop_의_후보가_없으면_500_이_아니라_409다() {
        // 남는 주문 하나의 후보만 없다. 내부 조인이던 때는 그 stop 이 다시 쓰기에서 순번을 받지 못해 옛 순번이
        // 새 순번과 겹쳤고, 커밋이 (route_id, seq) UNIQUE 로 터졌다(500). 근거: 관측(재현됨) — 음성 표본.
        TwoRoutes routes = twoRoutes();
        List<UUID> fromBefore = ordersOf(routes.fromRouteId());
        UUID expired = fromBefore.stream().filter(id -> !id.equals(routes.orderId())).findFirst().orElseThrow();
        assertThat(count("""
                SELECT count(*) FROM route_stop_orders WHERE stop_id =
                       (SELECT stop_id FROM route_stop_orders WHERE order_id = ?)""", expired))
                .as("전제 — 그 stop 의 주문은 그것 하나다").isOne();
        jdbc.update("DELETE FROM dispatch_candidates WHERE order_id = ?", expired);

        assertThatThrownBy(() -> reassign.reassign(routes.fromRouteId(), routes.orderId(), routes.toRouteId()))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(DispatchErrorCode.CANDIDATES_EXPIRED);
                    assertThat(e.details()).containsEntry("orderId", expired.toString());
                });
        assertThat(ordersOf(routes.fromRouteId())).isEqualTo(fromBefore);
    }

    @Test
    void 개정_스냅숏은_후보가_없는_stop_을_빼지_않고_실패한다() {
        // §6.10 취소와 §6.8 재계획의 개정 발행이 이 스냅숏에서 만든다 — 빠지면 발행된 개정에서 그 stop 이 사라진다.
        TwoRoutes routes = twoRoutes();
        UUID expired = ordersOf(routes.fromRouteId()).getFirst();
        jdbc.update("DELETE FROM dispatch_candidates WHERE order_id = ?", expired);

        assertThatThrownBy(() -> tx().execute(status -> routeMutations.snapshot(routes.fromRouteId())))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(DispatchErrorCode.CANDIDATES_EXPIRED));
    }

    @Test
    void 후보가_없는_주문은_취소된_것이_아니다() {
        // 내부 조인이던 때는 NOT EXISTS 가 빈 집합을 보고 「전부 취소됐다」를 참으로 만들었다.
        TwoRoutes routes = twoRoutes();
        UUID stopId = jdbc.queryForObject("""
                SELECT s.id FROM route_stops s WHERE s.route_id = ? ORDER BY s.seq LIMIT 1
                """, UUID.class, routes.fromRouteId());
        jdbc.update("""
                DELETE FROM dispatch_candidates c USING route_stop_orders o
                 WHERE c.order_id = o.order_id AND o.stop_id = ?
                """, stopId);

        Boolean cancelled = tx().execute(status -> routeMutations.cancelStopIfAllOrdersCancelled(stopId));

        assertThat(cancelled).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM route_stops WHERE id = ?", String.class, stopId))
                .isEqualTo("PLANNED");
    }

    // ------------------------------------------------------------------ 픽스처

    /** 계획 하나 — 라우트 하나, stop 마다 주문 하나, 주문마다 후보와 설명 하나. */
    private record Fixture(UUID planId, UUID waveId, List<UUID> orders) {

        PlanRef ref() {
            return new PlanRef(planId, waveId);
        }
    }

    /**
     * @param status      계획 상태
     * @param age         {@code finished_at} 의 나이 — {@code PLANNING} 이면 {@code finished_at} 은 비고
     *                    {@code started_at} 이 이 나이다
     * @param stopStatuses stop 마다의 상태. 없으면 라우트 없는 계획(실패 · 실행 중) — 설명과 후보는 하나씩 둔다
     */
    private Fixture plan(String status, Duration age, String... stopStatuses) {
        UUID planId = Ids.newId();
        UUID waveId = Ids.newId();
        Instant at = NOW.minus(age);
        @Nullable Instant finished = status.equals("PLANNING") ? null : at;
        jdbc.update("""
                INSERT INTO route_plans (id, wave_id, camp_id, status, strategy, mode, seed, rule_version,
                                         started_at, finished_at, total_cost_krw, assigned_count, unassigned_count,
                                         plan_duration_ms, depot_lat, depot_lng)
                VALUES (?, ?, ?, ?, 'sweep-greedy-nn+ls', 'FULL', 1, 1, ?, ?, 1, 0, 0, 1, 37.64, 127.03)
                """, planId, waveId, CAMP_ID, status, Timestamp.from(at.minus(Duration.ofHours(1))),
                finished == null ? null : Timestamp.from(finished));

        List<UUID> orders = new ArrayList<>();
        if (stopStatuses.length == 0) {
            orders.add(Ids.newId());
        } else {
            UUID routeId = Ids.newId();
            jdbc.update("""
                    INSERT INTO routes (id, plan_id, vehicle_id, seq_no, status, revision, stop_count, distance_m,
                                        duration_s, cost_krw)
                    VALUES (?, ?, ?, 1, 'PLANNED', 1, ?, 1, 1, 1)
                    """, routeId, planId, VEHICLE_ID, stopStatuses.length);
            for (int seq = 1; seq <= stopStatuses.length; seq++) {
                UUID stopId = Ids.newId();
                UUID orderId = Ids.newId();
                orders.add(orderId);
                jdbc.update("""
                        INSERT INTO route_stops (id, route_id, seq, lat, lng, planned_arrival, planned_departure,
                                                 service_s, status)
                        VALUES (?, ?, ?, 37.64, 127.03, ?, ?, 60, ?)
                        """, stopId, routeId, seq, Timestamp.from(at), Timestamp.from(at), stopStatuses[seq - 1]);
                jdbc.update("INSERT INTO route_stop_orders (stop_id, order_id) VALUES (?, ?)", stopId, orderId);
            }
        }
        for (UUID orderId : orders) {
            insertCandidate(orderId, waveId, at);
            jdbc.update("""
                    INSERT INTO plan_explanations (id, plan_id, order_id, vehicle_id, rule_name, outcome, detail)
                    VALUES (?, ?, ?, ?, NULL, 'ASSIGNED', '{}'::jsonb)
                    """, Ids.newId(), planId, orderId, VEHICLE_ID);
        }
        return new Fixture(planId, waveId, List.copyOf(orders));
    }

    private UUID orphanCandidate(UUID waveId, Duration age) {
        UUID orderId = Ids.newId();
        insertCandidate(orderId, waveId, NOW.minus(age));
        return orderId;
    }

    private void insertCandidate(UUID orderId, UUID waveId, Instant at) {
        jdbc.update("""
                INSERT INTO dispatch_candidates (order_id, wave_id, camp_id, lat, lng, geohash7, weight_g, volume_cm3,
                                                 promised_start, promised_end, service_seconds, priority, status,
                                                 created_at, updated_at)
                VALUES (?, ?, ?, 37.64, 127.03, 'wydm9qw', 1, 1, ?, ?, 60, 0, 'PLANNED', ?, ?)
                """, orderId, waveId, CAMP_ID, Timestamp.from(at), Timestamp.from(at.plus(Duration.ofHours(4))),
                Timestamp.from(at), Timestamp.from(at));
    }

    /** 그 계획 · 웨이브의 여섯 표 행 수 — 지우는 순서대로. */
    private Map<String, Long> rows(Fixture plan) {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        counts.put("route_stop_orders", count("""
                SELECT count(*) FROM route_stop_orders o JOIN route_stops s ON s.id = o.stop_id
                  JOIN routes r ON r.id = s.route_id WHERE r.plan_id = ?""", plan.planId()));
        counts.put("route_stops", count("""
                SELECT count(*) FROM route_stops s JOIN routes r ON r.id = s.route_id WHERE r.plan_id = ?""",
                plan.planId()));
        counts.put("routes", count("SELECT count(*) FROM routes WHERE plan_id = ?", plan.planId()));
        counts.put("plan_explanations", count("SELECT count(*) FROM plan_explanations WHERE plan_id = ?",
                plan.planId()));
        counts.put("dispatch_candidates", count("SELECT count(*) FROM dispatch_candidates WHERE wave_id = ?",
                plan.waveId()));
        counts.put("route_plans", count("SELECT count(*) FROM route_plans WHERE id = ?", plan.planId()));
        return counts;
    }

    private long count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Long.class, arg);
    }

    private boolean candidateExists(UUID orderId) {
        return count("SELECT count(*) FROM dispatch_candidates WHERE order_id = ?", orderId) == 1;
    }

    /**
     * 계획 2,000 · 라우트 6,000 · stop 12만 · 주문 12만 · 후보 4만 · 설명 4만.
     *
     * <p>계획 하나의 몫이 표 전체의 1/2,000 이라 앞머리 탐색이 순차 스캔을 이기는 크기다 — 운영(91일 · 3,640 계획)의
     * 비율과 같은 쪽이다([측정](docs/benchmarks/phase7-dispatch-retention.md) §4). 작은 표에서 옳은 계획은
     * 순차 스캔이고, 그 계획은 이 검사가 보려는 것을 말하지 않는다.
     */
    private void fillForPlans() {
        tx().executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO route_plans (id, wave_id, camp_id, status, started_at, finished_at, depot_lat, depot_lng)
                    SELECT gen_random_uuid(), gen_random_uuid(), ?, 'PUBLISHED', ?::timestamptz - make_interval(days => n % 90),
                           ?::timestamptz - make_interval(days => n % 90), 37.64, 127.03
                      FROM generate_series(1, 2000) n
                    """, CAMP_ID, Timestamp.from(NOW), Timestamp.from(NOW));
            jdbc.update("""
                    INSERT INTO routes (id, plan_id, vehicle_id, seq_no, status, revision, stop_count, distance_m,
                                        duration_s, cost_krw)
                    SELECT gen_random_uuid(), p.id, ?, k, 'PLANNED', 1, 20, 1, 1, 1
                      FROM route_plans p, generate_series(1, 3) k
                    """, VEHICLE_ID);
            jdbc.update("""
                    INSERT INTO route_stops (id, route_id, seq, lat, lng, planned_arrival, planned_departure,
                                             service_s, status)
                    SELECT gen_random_uuid(), r.id, k, 37.64, 127.03, ?, ?, 60, 'COMPLETED'
                      FROM routes r, generate_series(1, 20) k
                    """, Timestamp.from(NOW), Timestamp.from(NOW));
            jdbc.update("""
                    INSERT INTO route_stop_orders (stop_id, order_id) SELECT id, gen_random_uuid() FROM route_stops
                    """);
            jdbc.update("""
                    INSERT INTO dispatch_candidates (order_id, wave_id, camp_id, lat, lng, geohash7, weight_g,
                                                     volume_cm3, promised_start, promised_end, service_seconds,
                                                     priority, status, created_at, updated_at)
                    SELECT gen_random_uuid(), p.wave_id, p.camp_id, 37.64, 127.03, 'wydm9qw', 1, 1, p.started_at,
                           p.started_at, 60, 0, 'PLANNED', p.started_at, p.finished_at
                      FROM route_plans p, generate_series(1, 20) k
                    """);
            jdbc.update("""
                    INSERT INTO plan_explanations (id, plan_id, order_id, vehicle_id, rule_name, outcome, detail)
                    SELECT gen_random_uuid(), p.id, gen_random_uuid(), NULL, NULL, 'ASSIGNED', '{}'::jsonb
                      FROM route_plans p, generate_series(1, 20) k
                    """);
        });
    }

    /** 어댑터의 문장 그대로 준비하고({@code ?} → {@code $1}) 그 모드의 계획을 본다 — 실행하지 않는다. */
    private String explain(String mode, String sql, UUID key) {
        return tx().execute(status -> {
            try {
                jdbc.execute("SET LOCAL plan_cache_mode = " + mode);
                jdbc.execute("PREPARE retention_probe(uuid) AS " + sql.replace("?", "$1"));
                return String.join("\n", jdbc.queryForList(
                        "EXPLAIN EXECUTE retention_probe('" + key + "')", String.class));
            } finally {
                jdbc.execute("DEALLOCATE retention_probe");
                status.setRollbackOnly();
            }
        });
    }

    // ------------------------------------------------------------------ 재배정 픽스처 (DispatchAdminIT 와 같은 모양)

    private record TwoRoutes(UUID waveId, UUID fromRouteId, UUID toRouteId, UUID orderId) {
    }

    /** 가장 많이 실은 라우트에서 가장 적게 실은 라우트로 — 하드 룰 409 가 이 검사를 가리지 않게 한다. */
    private TwoRoutes twoRoutes() {
        UUID waveId = Ids.newId();
        seedCandidates(waveId, 40);
        runPlan.run(RunPlanCommand.of(waveId, CAMP_ID, CAMP, null));
        PlanView plan = tx().execute(status -> planQueries.findPlanByWave(waveId)).orElseThrow();
        assertThat(plan.routes()).as("재배정을 보려면 라우트가 둘 이상이어야 한다").hasSizeGreaterThan(1);
        List<PlanView.RouteSummary> byLoad = plan.routes().stream()
                .sorted(java.util.Comparator.comparingInt(PlanView.RouteSummary::stopCount)).toList();
        UUID fromRouteId = byLoad.getLast().routeId();
        RouteView from = tx().execute(status -> planQueries.findRoute(fromRouteId)).orElseThrow();
        assertThat(from.stops().stream().mapToInt(stop -> stop.orderIds().size()).sum())
                .as("전제 — 출발 라우트에 옮기지 않는 주문이 있다").isGreaterThan(1);
        return new TwoRoutes(waveId, fromRouteId, byLoad.getFirst().routeId(),
                from.stops().getFirst().orderIds().getFirst());
    }

    /** 약속 창의 기준은 {@link PlanningClock#PLAN_AT} 이다 — 재배정이 근무창을 본다(DispatchAdminIT 참고). */
    private void seedCandidates(UUID waveId, int count) {
        TimeWindow window = new TimeWindow(NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(5)));
        tx().executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                candidates.insertIfAbsent(DispatchCandidate.load(Ids.newId(), waveId, CAMP_ID, null,
                        GeoPoint.of(CAMP.lat() + 0.004d * (i % 8 + 1), CAMP.lng() + 0.005d * (i / 8 + 1)),
                        40_000, 80_000, false, false, window, 60, false, 0, NOW));
            }
        });
    }

    /** 라우트의 주문 — stop 순번, 주문 id 순. */
    private List<UUID> ordersOf(UUID routeId) {
        return jdbc.queryForList("""
                SELECT o.order_id FROM route_stops s JOIN route_stop_orders o ON o.stop_id = s.id
                 WHERE s.route_id = ? ORDER BY s.seq, o.order_id
                """, UUID.class, routeId);
    }
}
