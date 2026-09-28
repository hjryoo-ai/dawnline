package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.common.error.DomainException;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.common.fleet.FleetFeasibility;
import com.dawnline.dispatch.application.port.in.AssessFleetUseCase;
import com.dawnline.dispatch.application.port.in.ManageResourcesUseCase;
import com.dawnline.dispatch.application.port.in.ResourceViews;
import com.dawnline.dispatch.application.port.in.RunPlanCommand;
import com.dawnline.dispatch.application.port.in.RunPlanUseCase;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.application.port.out.VehicleCatalog;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.dispatch.domain.DispatchErrorCode;
import com.dawnline.dispatch.domain.optimizer.VehicleSpec;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
 * 함대 — 실현 가능성 판정과 비활성화가 <strong>실물 PostgreSQL 에서</strong> 계획과 같은 것을 보는가
 * (DESIGN.md §5.3 「함대」, ADR-067 결정 2 · 5).
 *
 * <p>캠프는 <strong>이 클래스의 픽스처 캠프</strong>다 — 시드 캠프의 차량 20대를 쓰면 판정의 수가 시드에 매달리고, 비활성화가
 * 시드 행을 바꾼다(되돌리기는 순차 실행에 기대는 장치다 — CLAUDE.md 「픽스처는 되돌리지 말고 만들고 지운다」). 룰은 전역 시드의
 * {@code max-stops}(120)를 그대로 받는다.
 */
@SpringBootTest(classes = DispatchApplication.class)
@Import(PlanningClock.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("FleetIT — 함대 판정 · 비활성화")
class FleetIT extends DispatchIntegrationTestBase {

    private static final UUID CAMP_ID = Ids.newId();
    private static final GeoPoint DEPOT = GeoPoint.of(37.500000, 127.000000);

    @Autowired
    private AssessFleetUseCase fleet;

    @Autowired
    private ManageResourcesUseCase resources;

    @Autowired
    private RunPlanUseCase runPlan;

    @Autowired
    private DispatchCandidateRepository candidates;

    @Autowired
    private VehicleCatalog vehicles;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 검사 대상은 판정과 SQL 이다 — 릴레이를 켜면 이 IT 가 만들지 않은 토픽으로 재시도한다(DispatchAdminIT 와 같다). */
    @DynamicPropertySource
    static void relayOff(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /** 픽스처 캠프의 것 전부를 지운다 — 계획 산출물 · 후보 · 차량. */
    @AfterEach
    void 픽스처_캠프를_지운다() {
        tx().executeWithoutResult(status -> {
            entityManager.createNativeQuery("""
                    DELETE FROM route_stop_orders WHERE stop_id IN (SELECT s.id FROM route_stops s
                      JOIN routes r ON r.id = s.route_id JOIN route_plans p ON p.id = r.plan_id WHERE p.camp_id = ?)
                    """).setParameter(1, CAMP_ID).executeUpdate();
            entityManager.createNativeQuery("""
                    DELETE FROM route_stops WHERE route_id IN (SELECT r.id FROM routes r
                      JOIN route_plans p ON p.id = r.plan_id WHERE p.camp_id = ?)
                    """).setParameter(1, CAMP_ID).executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM routes WHERE plan_id IN (SELECT id FROM route_plans WHERE camp_id = ?)")
                    .setParameter(1, CAMP_ID).executeUpdate();
            entityManager.createNativeQuery(
                    "DELETE FROM plan_explanations WHERE plan_id IN (SELECT id FROM route_plans WHERE camp_id = ?)")
                    .setParameter(1, CAMP_ID).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM route_plans WHERE camp_id = ?")
                    .setParameter(1, CAMP_ID).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM dispatch_candidates WHERE camp_id = ?")
                    .setParameter(1, CAMP_ID).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM vehicles WHERE camp_id = ?")
                    .setParameter(1, CAMP_ID).executeUpdate();
        });
    }

    // ---------------------------------------------------------------- 판정

    @Test
    void 판정은_통합_후_stop_으로_재고_계획이_볼_후보를_센다() {
        vehicle("FL-V1-" + suffix(), false, false, 45_000, LocalTime.of(6, 0), LocalTime.of(22, 0));
        UUID waveId = Ids.newId();
        // 같은 건물 · 같은 창 · 같은 조합의 열 건 → stop 하나(§6.5 1단계). 냉장 한 건은 조합이 달라 따로 선다(ADR-033 의 통합 키).
        for (int i = 0; i < 10; i++) {
            candidate(waveId, DEPOT.lat() + 0.01d, DEPOT.lng(), 10_000, false);
        }
        candidate(waveId, DEPOT.lat() + 0.01d, DEPOT.lng(), 10_000, true);

        ResourceViews.FleetFeasibilityView view = fleet.assess(waveId);

        assertThat(view.candidates()).isEqualTo(11);
        assertThat(view.stops()).as("통합 후 — 원본 후보로 재면 통합의 전파가 기준에서 빠진다").isEqualTo(2);
        assertThat(view.campId()).isEqualTo(CAMP_ID);
        assertThat(view.maxStopsPerRoute()).as("전역 시드 룰의 max-stops").isEqualTo(120);
        assertThat(view.headroomPercent()).isEqualTo(FleetFeasibility.HEADROOM_PERCENT);
        assertThat(view.combinations()).hasSize(8);
        ResourceViews.FleetLineView general = general(view);
        assertThat(general.demandStops()).isEqualTo(2);
        assertThat(general.demandWeightG()).isEqualTo(110_000);
    }

    @Test
    void 부족하면_템플릿_몇_대가_모자라는지_말하고_근무창이_겹치지_않는_차량은_세지_않는다() {
        String van = "FL-V1-" + suffix();
        vehicle(van, false, false, 45_000, LocalTime.of(6, 0), LocalTime.of(22, 0));
        vehicle("FL-T1-" + suffix(), true, false, 87_000, LocalTime.of(6, 0), LocalTime.of(22, 0));
        // 야간조 — 약속창(10:00–14:00 KST)과 겹치지 않는다. 세면 부족분이 조용히 사라진다.
        vehicle("FL-N1-" + suffix(), false, false, 45_000, LocalTime.of(23, 0), LocalTime.of(8, 0));
        UUID waveId = Ids.newId();
        // 일반 수요 2,000 kg — 밴 400 + 트럭 1,200 = 1,600 kg 의 80% 로는 모자란다.
        //   100 × 2,000,000 ≤ 80 × (1,600,000 + 400,000 n) → n ≥ 2.25 → 3
        for (int i = 0; i < 50; i++) {
            candidate(waveId, DEPOT.lat() + 0.002d * (i % 10 + 1), DEPOT.lng() + 0.002d * (i / 10 + 1), 40_000, false);
        }

        ResourceViews.FleetFeasibilityView view = fleet.assess(waveId);

        assertThat(view.fleet()).as("주간 두 대 — 야간조는 이 웨이브의 용량이 아니다").isEqualTo(2);
        ResourceViews.FleetLineView general = general(view);
        assertThat(general.status()).isEqualTo(FleetFeasibility.Status.SHORTFALL);
        assertThat(general.shortfall()).isEqualTo(3);
        assertThat(general.template()).isNotNull();
        assertThat(general.template().code()).as("냉장·위험물이 정확히 일반인 가장 싼 차").isEqualTo(van);
        assertThat(general.template().shiftStart()).isEqualTo(LocalTime.of(6, 0));
        assertThat(view.feasible()).isFalse();
    }

    @Test
    void 부족한데_템플릿이_없으면_NO_TEMPLATE_이다() {
        vehicle("FL-V1-" + suffix(), false, false, 45_000, LocalTime.of(6, 0), LocalTime.of(22, 0));
        UUID waveId = Ids.newId();
        candidate(waveId, DEPOT.lat() + 0.01d, DEPOT.lng(), 10_000, true);

        ResourceViews.FleetLineView cold = fleet.assess(waveId).combinations().stream()
                .filter(line -> line.cold() && !line.hazmat() && !line.large())
                .findFirst().orElseThrow();

        assertThat(cold.status()).isEqualTo(FleetFeasibility.Status.NO_TEMPLATE);
        assertThat(cold.shortfall()).isNull();
        assertThat(cold.template()).isNull();
    }

    @Test
    void 발행된_계획이_있는_웨이브는_409_wave_already_planned_다() {
        vehicle("FL-V1-" + suffix(), false, false, 45_000, LocalTime.of(6, 0), LocalTime.of(22, 0));
        UUID waveId = Ids.newId();
        candidate(waveId, DEPOT.lat() + 0.01d, DEPOT.lng(), 10_000, false);
        runPlan.run(RunPlanCommand.of(waveId, CAMP_ID, DEPOT, null));

        assertThatThrownBy(() -> fleet.assess(waveId))
                .isInstanceOfSatisfying(DomainException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(DispatchErrorCode.WAVE_ALREADY_PLANNED));
    }

    @Test
    void 계획_대상_후보가_없는_웨이브는_404_다() {
        assertThatThrownBy(() -> fleet.assess(Ids.newId())).isInstanceOf(NotFoundException.class);
    }

    // ---------------------------------------------------------------- 비활성화

    @Test
    void 끝나지_않은_stop_이_있으면_409_이고_끝나면_비활성화되어_다음_계획에서_빠진다() {
        UUID vehicleId = vehicle("FL-V1-" + suffix(), false, false, 45_000, LocalTime.of(6, 0), LocalTime.of(22, 0));
        UUID waveId = Ids.newId();
        for (int i = 0; i < 3; i++) {
            candidate(waveId, DEPOT.lat() + 0.004d * (i + 1), DEPOT.lng(), 10_000, false);
        }
        runPlan.run(RunPlanCommand.of(waveId, CAMP_ID, DEPOT, null));
        long planned = unfinishedStopsOf(vehicleId);
        assertThat(planned).as("전제 — 계획이 이 차량에 stop 을 실었다").isPositive();

        assertThatThrownBy(() -> resources.deactivateVehicle(vehicleId))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(DispatchErrorCode.VEHICLE_IN_SERVICE);
                    assertThat(e.details()).containsEntry("unfinishedStops", planned);
                });
        assertThat(vehicleView(vehicleId).active()).as("409 는 아무것도 바꾸지 않는다").isTrue();

        // 기사가 끝낸 것처럼 — 종결 상태면 보존과 같은 정의로 「끝났다」
        tx().executeWithoutResult(status -> entityManager.createNativeQuery("""
                UPDATE route_stops SET status = 'COMPLETED'
                 WHERE route_id IN (SELECT id FROM routes WHERE vehicle_id = ?)
                """).setParameter(1, vehicleId).executeUpdate());

        ResourceViews.VehicleView deactivated = resources.deactivateVehicle(vehicleId);

        assertThat(deactivated.active()).isFalse();
        List<VehicleSpec> available = tx().execute(status -> vehicles.availableAt(CAMP_ID, PlanningClock.PLAN_AT,
                new com.dawnline.common.TimeWindow(PlanningClock.PLAN_AT, PlanningClock.PLAN_AT.plus(java.time.Duration.ofHours(6))),
                Ids.newId()));
        assertThat(available).as("비활성 차량은 계획이 받는 함대에 없다")
                .noneMatch(spec -> spec.id().value().equals(vehicleId));
        assertThat(routesOf(vehicleId)).as("과거 라우트는 그대로다").isPositive();
    }

    @Test
    void 취소된_stop_만_남았으면_끝난_것이다() {
        UUID vehicleId = vehicle("FL-V1-" + suffix(), false, false, 45_000, LocalTime.of(6, 0), LocalTime.of(22, 0));
        UUID waveId = Ids.newId();
        candidate(waveId, DEPOT.lat() + 0.01d, DEPOT.lng(), 10_000, false);
        runPlan.run(RunPlanCommand.of(waveId, CAMP_ID, DEPOT, null));
        assertThat(unfinishedStopsOf(vehicleId)).as("전제").isPositive();
        tx().executeWithoutResult(status -> entityManager.createNativeQuery("""
                UPDATE route_stops SET status = 'CANCELLED'
                 WHERE route_id IN (SELECT id FROM routes WHERE vehicle_id = ?)
                """).setParameter(1, vehicleId).executeUpdate());

        assertThat(resources.deactivateVehicle(vehicleId).active()).isFalse();
    }

    @Test
    void 이미_비활성이면_바꾸는_것_없이_그대로_돌려준다() {
        UUID vehicleId = vehicle("FL-V1-" + suffix(), false, false, 45_000, LocalTime.of(6, 0), LocalTime.of(22, 0));
        resources.deactivateVehicle(vehicleId);

        ResourceViews.VehicleView again = resources.deactivateVehicle(vehicleId);

        assertThat(again.active()).isFalse();
    }

    @Test
    void 없는_차량의_비활성화는_찾지_못했다고_한다() {
        assertThatThrownBy(() -> resources.deactivateVehicle(Ids.newId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 증차의_출처는_칸이_말한다() {
        UUID id = resources.createVehicle(new ResourceViews.NewVehicle(CAMP_ID, "FL-PS-" + suffix(), "VAN",
                400_000, 1_200_000, false, false, 45_000, 600, 250, LocalTime.of(23, 0), LocalTime.of(8, 0),
                "peak-sim"));

        assertThat(vehicleView(id).source()).isEqualTo("peak-sim");
    }

    @Test
    void 같은_코드의_차량은_409_vehicle_code_taken_이고_있는_차량을_가리킨다() {
        String code = "FL-DUP-" + suffix().substring(0, 6);
        UUID first = vehicle(code, false, false, 45_000, LocalTime.of(6, 0), LocalTime.of(22, 0));

        assertThatThrownBy(() -> vehicle(code, false, false, 45_000, LocalTime.of(6, 0), LocalTime.of(22, 0)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(DispatchErrorCode.VEHICLE_CODE_TAKEN);
                    assertThat(e.details()).containsEntry("vehicleId", first.toString());
                });
    }

    // ---------------------------------------------------------------- 픽스처

    private UUID vehicle(String code, boolean cold, boolean hazmat, int fixedCost, LocalTime start, LocalTime end) {
        boolean truck = code.contains("-T");
        return resources.createVehicle(new ResourceViews.NewVehicle(CAMP_ID, code, truck ? "TRUCK" : "VAN",
                truck ? 1_200_000 : 400_000, truck ? 4_000_000 : 1_200_000, cold, hazmat, fixedCost, 600, 250,
                start, end, null));
    }

    /** 약속창은 {@link PlanningClock#PLAN_AT}(09:00 KST)에서 파생한다 — 10:00–14:00 KST, 주간조 한가운데. */
    private void candidate(UUID waveId, double lat, double lng, int weightG, boolean cold) {
        Instant now = PlanningClock.PLAN_AT.truncatedTo(ChronoUnit.MICROS);
        TimeWindow window = new TimeWindow(now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(5)));
        tx().executeWithoutResult(status -> candidates.insertIfAbsent(DispatchCandidate.load(Ids.newId(), waveId,
                CAMP_ID, null, GeoPoint.of(lat, lng), weightG, 20_000, cold, false, window, 60, false, 0, now)));
    }

    private ResourceViews.VehicleView vehicleView(UUID id) {
        return resources.listVehicles(CAMP_ID).stream().filter(view -> view.id().equals(id)).findFirst().orElseThrow();
    }

    private long unfinishedStopsOf(UUID vehicleId) {
        return tx().execute(status -> ((Number) entityManager.createNativeQuery("""
                SELECT count(*) FROM routes r JOIN route_stops s ON s.route_id = r.id
                 WHERE r.vehicle_id = ? AND s.status NOT IN ('CANCELLED', 'COMPLETED', 'FAILED')
                """).setParameter(1, vehicleId).getSingleResult()).longValue());
    }

    private long routesOf(UUID vehicleId) {
        return tx().execute(status -> ((Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM routes WHERE vehicle_id = ?").setParameter(1, vehicleId)
                .getSingleResult()).longValue());
    }

    private static ResourceViews.FleetLineView general(ResourceViews.FleetFeasibilityView view) {
        return view.combinations().stream()
                .filter(line -> !line.cold() && !line.hazmat() && !line.large())
                .findFirst().orElseThrow();
    }

    private static String suffix() {
        return Ids.newId().toString().substring(28);
    }
}
