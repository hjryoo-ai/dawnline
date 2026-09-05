package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.dispatch.application.port.in.PlanView;
import com.dawnline.dispatch.application.port.in.ReassignStopUseCase;
import com.dawnline.dispatch.application.port.in.ResourceViews;
import com.dawnline.dispatch.application.port.in.RouteView;
import com.dawnline.dispatch.application.port.in.RunPlanCommand;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.dispatch.application.port.in.ManageResourcesUseCase;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.application.port.out.PlanQueries;
import com.dawnline.dispatch.domain.DispatchCandidate;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운영자 조회·재배정·참조 데이터 SQL 이 <strong>실물 PostgreSQL 에서</strong> 도는가 (Phase 3-5c).
 *
 * <p>이 세 어댑터({@code JdbcPlanQueries}, {@code JdbcRouteMutations}, {@code JdbcReferenceAdmin})는
 * 전부 네이티브 SQL 이다 — 컴파일러도 Hibernate 도 검사해 주지 않는다. 컬럼 순서 하나가 밀리면
 * {@code ClassCastException} 이 나고, {@code jsonb} 캐스팅이나 {@code TIME} 매핑은 드라이버가
 * 무엇을 돌려주느냐에 달려 있다(5b 에서 {@code LocalTime}/{@code Time} 로 한 번 데였다).
 *
 * <p>릴레이는 켜지 않는다. 검사 대상은 발행이 아니라 SQL 이고, {@code ReassignStopService} 는
 * outbox 까지만 쓰면 충분하다.
 */
@SpringBootTest(classes = DispatchApplication.class)
@Import(PlanningClock.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("DispatchAdminIT — 조회·재배정·참조 데이터 SQL")
class DispatchAdminIT extends DispatchIntegrationTestBase {

    private static final tools.jackson.databind.ObjectMapper JSON =
            new tools.jackson.databind.ObjectMapper();

    /** 시드의 첫 캠프 (서울 북부). */
    private static final UUID CAMP_ID = UUID.fromString("01a06edd-6c00-7000-8001-000000000001");
    private static final GeoPoint CAMP = GeoPoint.of(37.640000, 127.030000);

    @Autowired
    private RunPlanUseCase runPlan;

    @Autowired
    private DispatchCandidateRepository candidates;

    @Autowired
    private PlanQueries planQueries;

    @Autowired
    private ReassignStopUseCase reassign;

    @Autowired
    private ManageResourcesUseCase resources;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * 릴레이를 끈다. 검사 대상은 발행이 아니라 SQL 이고, 켜 두면 이 IT 가 만들지 않은 토픽으로
     * 계속 재시도하며 로그를 채운다. {@code ReassignStopService} 의 발행은 {@code outbox_events}
     * 행으로 확인한다 — 브로커까지 가는 것은 {@code PlanExecutionIT} 가 이미 본다.
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void relayOff(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /** 이 테스트가 만든 차량·기사. 참조 데이터는 {@link #clean()} 이 지우지 않는다. */
    private final List<UUID> created = new ArrayList<>();

    /** 이 테스트가 고친 룰의 원래 값. */
    private @org.jspecify.annotations.Nullable RuleBackup ruleBackup;

    private record RuleBackup(UUID id, String params, boolean enabled, int ruleVersion) {
    }

    @BeforeEach
    void clean() {
        tx().executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM plan_explanations").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_stop_orders").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_stops").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM routes").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_plans").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM dispatch_candidates").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM outbox_events").executeUpdate();
        });
    }

    /**
     * 참조 데이터를 원래대로 돌려놓는다.
     *
     * <p>{@link #clean()} 은 계획 산출물만 지운다 — 시드(룰·차량·기사)는 <em>픽스처</em>이고,
     * {@code DispatchSeedCoverageIT} 가 그것이 계약 파일과 정확히 같은지 본다. 이 클래스가
     * 만들거나 고친 것을 남겨 두면 그 IT 가 실행 순서에 따라 깨진다 — 실제로 그렇게 깨뜨렸다.
     *
     * <p><strong>이 되돌리기는 순차 실행에 기대고 있다.</strong> 통합 테스트가 병렬로 돌기
     * 시작하면 두 테스트가 같은 시드 행을 동시에 보게 되고, 그때는 되돌려도 늦다. 옳은 형태는
     * 시드를 고치는 대신 <em>자기 픽스처 행</em>을 만들어 쓰는 것이고, 그 전환은
     * IMPLEMENTATION_PLAN Phase 4-9 에 적어 두었다.
     */
    @org.junit.jupiter.api.AfterEach
    void restoreReferenceData() {
        tx().executeWithoutResult(status -> {
            for (UUID id : created) {
                entityManager.createNativeQuery("DELETE FROM drivers WHERE id = ? OR vehicle_id = ?")
                        .setParameter(1, id).setParameter(2, id).executeUpdate();
                entityManager.createNativeQuery("DELETE FROM vehicles WHERE id = ?")
                        .setParameter(1, id).executeUpdate();
            }
            if (ruleBackup != null) {
                entityManager.createNativeQuery("""
                        UPDATE dispatch_rules
                           SET params = cast(? as jsonb), enabled = ?, rule_version = ?
                         WHERE id = ?
                        """)
                        .setParameter(1, ruleBackup.params()).setParameter(2, ruleBackup.enabled())
                        .setParameter(3, ruleBackup.ruleVersion()).setParameter(4, ruleBackup.id())
                        .executeUpdate();
            }
        });
        created.clear();
        ruleBackup = null;
    }

    // ---------------------------------------------------------------- 조회

    @Test
    void 계획_조회가_라우트_요약과_설명을_함께_돌려준다() {
        UUID waveId = Ids.newId();
        List<UUID> orderIds = seedCandidates(waveId, 8);
        runPlan.run(RunPlanCommand.of(waveId, CAMP_ID, CAMP));

        PlanView plan = tx().execute(status -> planQueries.findPlanByWave(waveId)).orElseThrow();

        assertThat(plan.waveId()).isEqualTo(waveId);
        assertThat(plan.campId()).isEqualTo(CAMP_ID);
        assertThat(plan.status()).isEqualTo("PUBLISHED");
        assertThat(plan.strategy()).isNotBlank();
        assertThat(plan.totalCostKrw()).isNotNull().isPositive();
        assertThat(plan.routes()).isNotEmpty();
        // 설명은 주문마다 하나다 — 배정됐든 안 됐든 "왜" 가 있어야 한다(§6.9).
        assertThat(plan.explanations()).hasSize(orderIds.size());
        assertThat(plan.explanations()).allSatisfy(view ->
                assertThat(view.detail()).isNotBlank());
        assertThat(plan.routes()).isSortedAccordingTo(
                java.util.Comparator.comparingInt(PlanView.RouteSummary::seqNo));
    }

    @Test
    void 같은_계획을_식별자로도_찾는다() {
        UUID waveId = Ids.newId();
        seedCandidates(waveId, 4);
        runPlan.run(RunPlanCommand.of(waveId, CAMP_ID, CAMP));
        UUID planId = tx().execute(status -> planQueries.findPlanByWave(waveId))
                .orElseThrow().planId();

        Optional<PlanView> found = tx().execute(status -> planQueries.findPlan(planId));

        assertThat(found).hasValueSatisfying(plan -> assertThat(plan.waveId()).isEqualTo(waveId));
    }

    @Test
    void 없는_계획은_빈_값이다() {
        Optional<PlanView> byId = tx().execute(status -> planQueries.findPlan(Ids.newId()));
        Optional<PlanView> byWave = tx().execute(status -> planQueries.findPlanByWave(Ids.newId()));

        assertThat(byId).isEmpty();
        assertThat(byWave).isEmpty();
    }

    @Test
    void 라우트_조회가_stop_과_주문을_순서대로_돌려준다() {
        UUID waveId = Ids.newId();
        seedCandidates(waveId, 8);
        runPlan.run(RunPlanCommand.of(waveId, CAMP_ID, CAMP));
        PlanView plan = tx().execute(status -> planQueries.findPlanByWave(waveId)).orElseThrow();
        UUID routeId = plan.routes().getFirst().routeId();

        RouteView route = tx().execute(status -> planQueries.findRoute(routeId)).orElseThrow();

        assertThat(route.routeId()).isEqualTo(routeId);
        assertThat(route.planId()).isEqualTo(plan.planId());
        // 최초 확정이 0 이 아니라 1 이다 (§6.8 4단계, routes.revision DEFAULT 1).
        assertThat(route.revision()).isOne();
        assertThat(route.stops()).isNotEmpty()
                .isSortedAccordingTo(java.util.Comparator.comparingInt(RouteView.StopView::seq));
        // NUMERIC(9,6) 이 double 로 돌아오는지 — 캠프 부근이어야 한다.
        assertThat(route.stops()).allSatisfy(stop -> {
            assertThat(stop.lat()).isBetween(37.0, 38.0);
            assertThat(stop.lng()).isBetween(126.0, 128.0);
            assertThat(stop.orderIds()).isNotEmpty();
        });
        // 요약의 stop_count 와 상세의 stop 수가 같아야 한다 — 다르면 운영자 화면 두 곳이
        // 같은 라우트에 대해 다른 숫자를 보여 준다.
        assertThat(route.stops()).hasSize(plan.routes().getFirst().stopCount());
    }

    @Test
    void 없는_라우트는_빈_값이다() {
        Optional<RouteView> found = tx().execute(status -> planQueries.findRoute(Ids.newId()));

        assertThat(found).isEmpty();
    }

    // ---------------------------------------------------------------- 재배정

    @Test
    void 재배정이_주문을_옮기고_양쪽_개정을_올린다() {
        TwoRoutes routes = twoRoutes();

        ReassignStopUseCase.Result result = reassign.reassign(
                routes.fromRouteId(), routes.orderId(), routes.toRouteId());

        assertThat(result.orderId()).isEqualTo(routes.orderId());
        assertThat(result.fromRevision()).as("1 에서 올라간다").isEqualTo(2);
        assertThat(result.toRevision()).isEqualTo(2);

        RouteView target = tx().execute(status -> planQueries.findRoute(routes.toRouteId()))
                .orElseThrow();
        assertThat(target.stops().stream().flatMap(stop -> stop.orderIds().stream()))
                .contains(routes.orderId());
        RouteView source = tx().execute(status -> planQueries.findRoute(routes.fromRouteId()))
                .orElseThrow();
        assertThat(source.stops().stream().flatMap(stop -> stop.orderIds().stream()))
                .doesNotContain(routes.orderId());
        // 순번은 1..n 으로 다시 매겨진다 — UNIQUE (route_id, seq) 가 살아 있어야 한다.
        assertThat(target.stops().stream().map(RouteView.StopView::seq).toList())
                .isEqualTo(java.util.stream.IntStream.rangeClosed(1, target.stops().size())
                        .boxed().toList());
    }

    @Test
    void 재배정은_두_라우트의_route_assigned_를_개정과_함께_남긴다() {
        TwoRoutes routes = twoRoutes();
        // 계획이 이미 라우트마다 route.assigned 를 넣어 두었다. 그 발행은 PlanExecutionIT 의
        // 몫이므로 지우고, 재배정이 <em>더</em> 넣는 것만 본다.
        tx().executeWithoutResult(status ->
                entityManager.createNativeQuery("DELETE FROM outbox_events").executeUpdate());

        reassign.reassign(routes.fromRouteId(), routes.orderId(), routes.toRouteId());

        List<String> payloads = published(routes.fromRouteId(), routes.toRouteId());
        assertThat(payloads).as("옮긴 쪽과 받은 쪽 둘 다").hasSize(2);
        // 개정 없이 나가면 소비자가 어느 것이 최신인지 알 수 없다 (ADR-026, §6.8 4단계).
        // 문자열로 찾지 않는다 — jsonb::text 는 "revision": 2 처럼 공백을 넣어 돌려준다.
        List<Integer> revisions = payloads.stream()
                .map(payload -> JSON.readTree(payload).get("revision").asInt()).toList();
        assertThat(revisions).containsExactly(2, 2);
    }

    @SuppressWarnings("unchecked")
    private List<String> published(UUID fromRouteId, UUID toRouteId) {
        return tx().execute(status -> (List<String>) entityManager.createNativeQuery("""
                SELECT payload::text FROM outbox_events
                 WHERE topic = 'dawnline.route.assigned.v1'
                   AND aggregate_id IN (?, ?)
                 ORDER BY aggregate_id
                """).setParameter(1, fromRouteId).setParameter(2, toRouteId).getResultList());
    }

    @Test
    void 없는_라우트로의_재배정은_찾지_못했다고_한다() {
        TwoRoutes routes = twoRoutes();
        UUID missing = Ids.newId();

        assertThatThrownBy(() -> reassign.reassign(routes.fromRouteId(), routes.orderId(), missing))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 순번_UNIQUE_는_지연_제약이라_통째로_뒤집어도_커밋된다() {
        // 전제를 먼저 말한다 — 제약이 즉시 검사면 아래 UPDATE 는 중간에서 터진다. V1 이 그랬고,
        // 어댑터는 순번을 1000 만큼 피신시켜 우회하고 있었다(V3 가 그 트릭을 없앴다).
        Object[] flags = tx().execute(status -> (Object[]) entityManager.createNativeQuery("""
                SELECT condeferrable, condeferred FROM pg_constraint
                 WHERE conname = 'route_stops_route_id_seq_key'
                """).getSingleResult());
        assertThat((Boolean) flags[0]).as("DEFERRABLE").isTrue();
        assertThat((Boolean) flags[1]).as("INITIALLY DEFERRED").isTrue();

        TwoRoutes routes = twoRoutes();
        Map<UUID, Integer> before = seqByStop(routes.fromRouteId());
        assertThat(before).as("뒤집을 것이 있어야 한다").hasSizeGreaterThan(1);
        int last = before.size() + 1;

        // 1..n → n..1. 한 문장이어도 PostgreSQL 은 행마다 유일성을 보므로, 지연 제약이 아니면
        // 첫 행에서 duplicate key 가 난다.
        tx().executeWithoutResult(status -> entityManager.createNativeQuery(
                        "UPDATE route_stops SET seq = ? - seq WHERE route_id = ?")
                .setParameter(1, (short) last).setParameter(2, routes.fromRouteId())
                .executeUpdate());

        Map<UUID, Integer> after = seqByStop(routes.fromRouteId());
        assertThat(after).allSatisfy((stopId, seq) ->
                assertThat(seq).isEqualTo(last - before.get(stopId)));
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, Integer> seqByStop(UUID routeId) {
        List<Object[]> rows = tx().execute(status -> (List<Object[]>) entityManager
                .createNativeQuery("SELECT id, seq FROM route_stops WHERE route_id = ?")
                .setParameter(1, routeId).getResultList());
        Map<UUID, Integer> byStop = new java.util.LinkedHashMap<>();
        rows.forEach(row -> byStop.put((UUID) row[0], ((Number) row[1]).intValue()));
        return byStop;
    }

    // ---------------------------------------------------------------- 참조 데이터

    @Test
    void 룰_목록은_전역_룰을_먼저_준다() {
        List<ResourceViews.RuleView> rules = tx().execute(status -> resources.listRules(CAMP_ID));

        assertThat(rules).isNotEmpty();
        assertThat(rules.getFirst().campId()).as("전역 룰이 먼저").isNull();
        // params 는 jsonb 다 — ::text 캐스팅이 빠지면 PGobject 가 튀어나온다.
        assertThat(rules).allSatisfy(rule -> assertThat(rule.params()).startsWith("{"));
    }

    @Test
    void 캠프를_주지_않으면_전역_룰만_나온다() {
        // camp_id = NULL 은 어떤 행과도 같지 않다. 널 파라미터를 네이티브 쿼리에 넘기는 것이
        // 드라이버에서 터지지 않는지도 여기서 확인한다.
        List<ResourceViews.RuleView> rules = tx().execute(status -> resources.listRules(null));

        assertThat(rules).isNotEmpty();
        assertThat(rules).allSatisfy(rule -> assertThat(rule.campId()).isNull());
    }

    @Test
    void 룰을_고치면_버전이_오른다() {
        // rule_version 이 오르지 않으면 계획이 어떤 룰로 돌았는지 사후에 알 수 없다(§6.9).
        ResourceViews.RuleView rule = tx().execute(status -> resources.listRules(CAMP_ID))
                .getFirst();
        ruleBackup = new RuleBackup(rule.id(), rule.params(), rule.enabled(), rule.ruleVersion());

        int next = tx().execute(status -> resources.updateRule(rule.id(),
                new ResourceViews.UpdateRule(Map.of("maxStops", 90), false)));

        assertThat(next).isEqualTo(rule.ruleVersion() + 1);
        ResourceViews.RuleView updated = tx().execute(status -> resources.listRules(CAMP_ID))
                .stream().filter(view -> view.id().equals(rule.id())).findFirst().orElseThrow();
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.params()).contains("maxStops").contains("90");
    }

    @Test
    void 없는_룰을_고치면_찾지_못했다고_한다() {
        UUID missing = Ids.newId();

        assertThatThrownBy(() -> tx().execute(status ->
                resources.updateRule(missing, new ResourceViews.UpdateRule(Map.of(), true))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 차량_목록의_근무_시각이_LocalTime_으로_돌아온다() {
        // TIME 컬럼은 드라이버 경로에 따라 java.sql.Time 으로 올 수도 있다 (5b).
        List<ResourceViews.VehicleView> vehicles =
                tx().execute(status -> resources.listVehicles(CAMP_ID));

        assertThat(vehicles).isNotEmpty();
        assertThat(vehicles).allSatisfy(vehicle -> {
            assertThat(vehicle.shiftStart()).isNotNull();
            assertThat(vehicle.shiftEnd()).isNotNull();
            assertThat(vehicle.campId()).isEqualTo(CAMP_ID);
        });
        assertThat(vehicles).isSortedAccordingTo(
                java.util.Comparator.comparing(ResourceViews.VehicleView::code));
    }

    @Test
    void 차량을_만들면_목록에_보인다() {
        String code = "IT-" + suffix();

        UUID id = tx().execute(status -> resources.createVehicle(new ResourceViews.NewVehicle(
                CAMP_ID, code, "VAN", 1_000_000, 5_000_000, true, false,
                40_000, 300, 120, LocalTime.of(2, 0), LocalTime.of(10, 0))));
        created.add(id);

        ResourceViews.VehicleView created = tx().execute(status -> resources.listVehicles(CAMP_ID))
                .stream().filter(view -> view.id().equals(id)).findFirst().orElseThrow();
        assertThat(created.code()).isEqualTo(code);
        assertThat(created.cold()).isTrue();
        assertThat(created.allowsHazmat()).isFalse();
        assertThat(created.shiftStart()).isEqualTo(LocalTime.of(2, 0));
        assertThat(created.active()).as("새 차량은 활성").isTrue();
    }

    @Test
    void 기사를_만들면_배정_가능_상태로_목록에_보인다() {
        String code = "DR-" + suffix();
        UUID vehicleId = tx().execute(status -> resources.listVehicles(CAMP_ID)).getFirst().id();

        UUID id = tx().execute(status -> resources.createDriver(
                new ResourceViews.NewDriver(CAMP_ID, vehicleId, code, "홍길동")));
        created.add(id);

        ResourceViews.DriverView created = tx().execute(status -> resources.listDrivers(CAMP_ID))
                .stream().filter(view -> view.id().equals(id)).findFirst().orElseThrow();
        assertThat(created.code()).isEqualTo(code);
        assertThat(created.vehicleId()).isEqualTo(vehicleId);
        assertThat(created.status()).isEqualTo("AVAILABLE");
    }

    @Test
    void 차량_없는_기사도_만들_수_있다() {
        // vehicle_id 는 nullable 이다 — 배정 전 기사가 있다.
        String code = "DR-" + suffix();

        UUID id = tx().execute(status -> resources.createDriver(
                new ResourceViews.NewDriver(CAMP_ID, null, code, "김아무개")));
        created.add(id);

        List<ResourceViews.DriverView> drivers =
                tx().execute(status -> resources.listDrivers(CAMP_ID));
        assertThat(drivers)
                .anySatisfy(view -> {
                    assertThat(view.id()).isEqualTo(id);
                    assertThat(view.vehicleId()).isNull();
                });
    }

    // ---------------------------------------------------------------- 도우미

    /** 두 라우트와, 첫 라우트에 실린 주문 하나. */
    private record TwoRoutes(UUID fromRouteId, UUID toRouteId, UUID orderId) {
    }

    private TwoRoutes twoRoutes() {
        UUID waveId = Ids.newId();
        seedCandidates(waveId, 40);
        runPlan.run(RunPlanCommand.of(waveId, CAMP_ID, CAMP));
        PlanView plan = tx().execute(status -> planQueries.findPlanByWave(waveId)).orElseThrow();
        assertThat(plan.routes()).as("재배정을 보려면 라우트가 둘 이상이어야 한다").hasSizeGreaterThan(1);

        // 가장 많이 실은 라우트에서 가장 적게 실은 라우트로 옮긴다. 아무 둘이나 고르면 목적지가
        // 이미 꽉 차 있어 하드 룰 위반(409)이 나고, 그것은 이 테스트가 보려는 것이 아니다 —
        // 목적지가 꽉 찬 경우는 ReassignStopServiceTest 가 단위로 본다.
        List<PlanView.RouteSummary> byLoad = plan.routes().stream()
                .sorted(java.util.Comparator.comparingInt(PlanView.RouteSummary::stopCount))
                .toList();
        UUID fromRouteId = byLoad.getLast().routeId();
        UUID toRouteId = byLoad.getFirst().routeId();
        RouteView from = tx().execute(status -> planQueries.findRoute(fromRouteId)).orElseThrow();
        UUID orderId = from.stops().getFirst().orderIds().getFirst();
        return new TwoRoutes(fromRouteId, toRouteId, orderId);
    }

    /** {@code code} 는 {@code VARCHAR(16)} 이다. UUIDv7 뒷자리(무작위부)를 쓴다. */
    private static String suffix() {
        return Ids.newId().toString().substring(24);
    }

    /**
     * 약속 창의 기준을 {@link PlanningClock#PLAN_AT} 에서 잡는다 — {@code Instant.now()} 가 아니다.
     *
     * <p>재배정은 "옮긴 뒤 복귀 시각이 근무 종료 − 30분 버퍼 안인가" 를 본다(§6.3). 21시에
     * 돌리면 남은 근무창이 한 시간이라 어떤 이동도 그 검사를 통과하지 못하고, 세 테스트가
     * {@code ConflictException} 으로 떨어진다 — 2026-09-05 에 실제로 그랬다. 이 클래스가 재는
     * 것은 재배정 규칙이지 <em>지금 몇 시인가</em>가 아니다.
     */
    private List<UUID> seedCandidates(UUID waveId, int count) {
        Instant now = PlanningClock.PLAN_AT.truncatedTo(ChronoUnit.MICROS);
        TimeWindow window = new TimeWindow(now.plus(Duration.ofHours(1)),
                now.plus(Duration.ofHours(5)));
        List<UUID> orderIds = new ArrayList<>(count);
        tx().executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                UUID orderId = Ids.newId();
                orderIds.add(orderId);
                candidates.insertIfAbsent(DispatchCandidate.load(orderId, waveId, CAMP_ID, null,
                        GeoPoint.of(CAMP.lat() + 0.004d * (i % 8 + 1),
                                CAMP.lng() + 0.005d * (i / 8 + 1)),
                        40_000, 80_000, false, false, window, 60, 0, now));
            }
        });
        return orderIds;
    }
}
