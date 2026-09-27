package com.dawnline.sim.fleet;

import static com.dawnline.sim.fleet.FakeOpsClient.assessment;
import static com.dawnline.sim.fleet.FakeOpsClient.line;
import static com.dawnline.sim.fleet.FakeOpsClient.van;
import static com.dawnline.sim.fleet.FakeOpsClient.wave;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.sim.config.SimProperties.Scenario.Fleet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 성수기 증차 (DESIGN.md §5.6 「성수기 증차」, ADR-067). */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PeakFleetTest {

    /** 창 끝 다음의 DAWN 컷오프 — 2026-09-28 00:00 KST. */
    private static final Instant CUTOFF = Instant.parse("2026-09-27T15:00:00Z");
    /** 증차 시각 — 23:58 KST. */
    private static final Clock AT_2358 = Clock.fixed(Instant.parse("2026-09-27T14:58:00Z"), ZoneOffset.UTC);
    private static final Instant CLOSED = CUTOFF.plusSeconds(90);

    private final FakeOpsClient ops = new FakeOpsClient();
    private final long[] slept = {0};

    private PeakFleet fleet(Clock clock) {
        return new PeakFleet(ops, clock, nanos -> slept[0] += nanos, Duration.ofSeconds(5), Duration.ofSeconds(60),
                Duration.ofSeconds(20), () -> "A1B2C3");
    }

    private void openWave() {
        ops.waveAnswers.add(List.of(wave(CUTOFF, "OPEN", null, null)));
    }

    private void plannedAfter(Instant closedAt) {
        ops.waveAnswers.add(List.of(wave(CUTOFF, "PLANNED", 12, closedAt)));
    }

    @Test
    void 부족분만큼_템플릿의_사본을_peak_sim_으로_더하고_이번_실행의_것만_비활성화한다() throws InterruptedException {
        openWave();
        ops.waveAnswers.add(List.of(wave(CUTOFF, "PLANNED", 3, CLOSED)));
        ops.feasibility = waveId -> assessment(line("냉장∧위험물", "FEASIBLE", 0, null),
                line("일반", "SHORTFALL", 3, van("seed", true)));
        ops.threeRoutes();
        PeakFleet.Session session = fleet(AT_2358).open(Fleet.FEASIBLE);

        session.provision(CUTOFF);
        session.awaitPlans();
        session.reassign();
        session.release();
        session.verifyAudit();

        assertThat(ops.commands).as("증차 → 그 직후 조기 마감 → 계획 뒤 재배정 → 비활성화 (결정 9 — 시각이 아니라 순서)")
                .containsExactly("ADD_VEHICLE", "ADD_VEHICLE", "ADD_VEHICLE", "CLOSE_WAVE", "REASSIGN_STOP");
        assertThat(ops.closeCalls).singleElement().asString().contains("peak-sim A1B2C3");
        assertThat(ops.addedBodies).hasSize(3).allSatisfy(body -> {
            assertThat(body.source()).isEqualTo("peak-sim");
            assertThat(body.type()).as("템플릿의 사본").isEqualTo("VAN");
            assertThat(body.shiftStart()).isEqualTo(van("seed", true).shiftStart());
            assertThat(body.code()).startsWith("PSA1B2C3-").hasSizeLessThanOrEqualTo(16);
        });
        assertThat(ops.addedBodies).extracting(OpsClient.NewVehicle::code).doesNotHaveDuplicates();
        assertThat(ops.deactivateCalls).as("더한 것 셋만").hasSize(3);
        assertThat(session.routes()).isEqualTo(3);
        FleetReport report = session.report();
        assertThat(report.isSuccess()).isTrue();
        assertThat(report.added()).isEqualTo(3);
        assertThat(report.deactivated()).isEqualTo(3);
        assertThat(report.auditRows()).as("등록 셋 + 조기 마감 + 재배정 + 비활성화 셋").isEqualTo(8);
        assertThat(report.audit()).as("캠프 하나 × 커맨드 넷, 모두 기대와 같다").hasSize(4)
                .allSatisfy(line -> assertThat(line.matches()).as(line.action().name()).isTrue());
        assertThat(report.toMarkdown()).contains("| 일반 | SHORTFALL | 3 | 3 |",
                "웨이브 1/1 에서 증차 완료 < 마감 · 가장 좁은 여유 210.0초 ✅", "9 / 4500 = 0.20% — §6.7 ≤ 0.5% ✅",
                "| ADD_VEHICLE | 3 | 3 | ✅ |", "| CLOSE_WAVE | 1 | 1 | ✅ |", "| REASSIGN_STOP | 1 | 1 | ✅ |",
                "| DEACTIVATE_VEHICLE | 3 | 3 (성공 3) | ✅ |", "옮겼다");
    }

    @Test
    void 재배정은_가장_많이_실은_라우트의_마지막_PLANNED_stop_을_그_차량의_능력을_갖춘_가장_적게_실은_라우트로_옮긴다()
            throws InterruptedException {
        // 마지막 stop 은 기사가 가장 늦게 닿는 곳이라 경합이 가장 작다 — 이미 닿은(ARRIVED) 넷째는 건너뛴다. 가장 적게 실은 LIGHT 는
        // 냉장 없는 밴이라 건너뛴다: 첫 peak-day 에서 능력을 보지 않고 고르자 열 번 모두 409 conflict 였다(2026-09-27).
        planned();

        assertThat(ops.reassignCalls).singleElement()
                .isEqualTo(List.of(FakeOpsClient.HEAVY, FakeOpsClient.LAST_PLANNED, FakeOpsClient.MIDDLE));
    }

    @Test
    void 능력을_갖춘_다른_라우트가_없으면_보내지_않고_이유를_남긴다() throws InterruptedException {
        openWave();
        ops.waveAnswers.add(List.of(wave(CUTOFF, "PLANNED", 2, CLOSED)));
        ops.feasibility = waveId -> assessment(line("일반", "SHORTFALL", 1, van("seed", true)));
        ops.threeRoutes();
        ops.routes = List.of(ops.routes.get(1), ops.routes.get(2));
        PeakFleet.Session session = fleet(AT_2358).open(Fleet.FEASIBLE);
        session.provision(CUTOFF);
        session.awaitPlans();
        session.reassign();

        assertThat(ops.reassignCalls).isEmpty();
        assertThat(session.report().toMarkdown()).contains("보내지 않음 — 가장 많이 실은 라우트의 차량(TRUCK) 능력을 모두 갖춘");
    }

    @Test
    void 사람이_읽는_id_는_가까운_때_만든_것끼리도_갈린다() {
        // 전제 — 둘의 앞 8자(UUIDv7 의 시각)가 같다. 앞 8자를 쓰면 첫 peak-day 리포트처럼 열 캠프가 한 이름이 된다(§13 축 11).
        java.util.UUID first = java.util.UUID.fromString("01a06edd-6c00-7000-8001-000000000001");
        java.util.UUID second = java.util.UUID.fromString("01a06edd-6c00-7000-8001-000000000002");
        assertThat(first.toString().substring(0, 8)).isEqualTo(second.toString().substring(0, 8));

        assertThat(PeakFleet.short8(first)).isNotEqualTo(PeakFleet.short8(second)).hasSize(8);
    }

    @Test
    void 시간_예산_줄은_웨이브마다_견준다() {
        // 결정 9 아래에서는 캠프마다 증차 직후 닫는다 — 한 캠프의 마감(23:58:03)이 다른 캠프의 증차 완료(23:58:14)보다 이르다.
        // 가로질러 견주면 멀쩡한 실행이 ✗ 였다(첫 peak-day, 2026-09-27).
        Instant base = Instant.parse("2026-09-27T14:58:00Z");
        FleetReport report = new FleetReport(Fleet.FEASIBLE, List.of(
                new FleetReport.WaveLine(FakeOpsClient.CAMP, FakeOpsClient.WAVE, 1, 1, 0, 1, 1, base.plusSeconds(2),
                        base.plusSeconds(3)),
                new FleetReport.WaveLine(FakeOpsClient.CAMP, FakeOpsClient.HEAVY, 1, 1, 0, 1, 1, base.plusSeconds(13),
                        base.plusSeconds(14))), List.of(), 2, 2, 4, List.of(), List.of(), List.of());

        assertThat(report.toMarkdown()).contains("웨이브 2/2 에서 증차 완료 < 마감 · 가장 좁은 여유 1.0초 ✅");
    }

    @Test
    void 조기_마감이_wave_not_open_이면_스케줄러가_먼저_닫은_것이고_시간_예산_실패다() {
        // 구조로 참인 어설션을 지우지 않은 이유가 이 날이다 — 증차가 컷오프 + grace 를 넘겼다.
        openWave();
        ops.feasibility = waveId -> assessment(line("일반", "SHORTFALL", 1, van("seed", true)));
        ops.closeAnswers.add(new OpsClient.Reply(409, ops.audit(), "wave-not-open", null));
        PeakFleet.Session session = fleet(AT_2358).open(Fleet.FEASIBLE);

        assertThatThrownBy(() -> session.provision(CUTOFF)).isInstanceOf(FleetFailure.class)
                .hasMessageContaining("시간 예산").hasMessageContaining("스케줄러가 먼저 닫았다");
        assertThat(session.addedCount()).as("더한 차량은 남아 있다 — 정리는 돈다").isEqualTo(1);
    }

    @Test
    void 재배정의_409_는_늦었다이고_세기만_한다() throws InterruptedException {
        ops.reassignStatus.add(409);
        PeakFleet.Session session = planned();
        session.release();
        session.verifyAudit();

        FleetReport report = session.report();
        assertThat(report.isSuccess()).as("기사가 먼저 닿은 것은 도구의 결함이 아니다").isTrue();
        assertThat(report.reassigns()).singleElement().satisfies(reassign -> {
            assertThat(reassign.status()).isEqualTo(409);
            assertThat(reassign.code()).isEqualTo("stop-not-planned");
        });
        assertThat(report.toMarkdown()).contains("409 stop-not-planned — 기사가 먼저 닿았다(늦었다)",
                "| REASSIGN_STOP | 1 | 1 | ✅ |");
    }

    @Test
    void 재배정의_409_가_아닌_거절은_실패다() {
        openWave();
        ops.waveAnswers.add(List.of(wave(CUTOFF, "PLANNED", 3, CLOSED)));
        ops.feasibility = waveId -> assessment(line("일반", "SHORTFALL", 1, van("seed", true)));
        ops.threeRoutes();
        ops.reassignStatus.add(502);
        PeakFleet.Session session = fleet(AT_2358).open(Fleet.FEASIBLE);

        assertThatThrownBy(() -> {
            session.provision(CUTOFF);
            session.awaitPlans();
            session.reassign();
        }).isInstanceOf(FleetFailure.class).hasMessageContaining("재배정 거절").hasMessageContaining("502");
    }

    @Test
    void 옮길_곳이_없으면_보내지_않고_기대도_0_이다() throws InterruptedException {
        openWave();
        ops.waveAnswers.add(List.of(wave(CUTOFF, "PLANNED", 1, CLOSED)));
        ops.feasibility = waveId -> assessment(line("일반", "SHORTFALL", 1, van("seed", true)));
        ops.routes = List.of(new OpsClient.RouteSummary(FakeOpsClient.LIGHT, null, 7));
        PeakFleet.Session session = fleet(AT_2358).open(Fleet.FEASIBLE);
        session.provision(CUTOFF);
        session.awaitPlans();
        session.reassign();
        session.release();
        session.verifyAudit();

        assertThat(ops.reassignCalls).isEmpty();
        FleetReport report = session.report();
        assertThat(report.isSuccess()).isTrue();
        assertThat(report.toMarkdown()).contains("보내지 않음 — 라우트 1 개", "| REASSIGN_STOP | 0 | 0 | ✅ |");
    }

    @Test
    void 읽기_모델의_라우트가_계획의_수만큼_오지_않으면_상한_뒤에_실패한다() {
        openWave();
        plannedAfter(CLOSED);
        ops.feasibility = waveId -> assessment(line("일반", "SHORTFALL", 1, van("seed", true)));
        ops.threeRoutes();
        PeakFleet.Session session = fleet(AT_2358).open(Fleet.FEASIBLE);

        assertThatThrownBy(() -> {
            session.provision(CUTOFF);
            session.awaitPlans();
            session.reassign();
        }).isInstanceOf(FleetFailure.class).hasMessageContaining("12 개 중 3 개만");
        assertThat(slept[0]).as("상한까지 기다렸다").isGreaterThanOrEqualTo(Duration.ofSeconds(60).toNanos());
    }

    @Test
    void 감사_행이_기대와_다르면_실패다() throws InterruptedException {
        // 조기 마감이 감사 id 없이 200 이다 — ops-api 가 행을 남기지 않았거나 도구가 헤더를 잃었다. 세기만 하면 보이지 않는다.
        ops.closeAnswers.add(new OpsClient.Reply(200, null, null, null));
        PeakFleet.Session session = planned();
        session.release();

        assertThatThrownBy(session::verifyAudit).isInstanceOf(FleetFailure.class)
                .hasMessageContaining("CLOSE_WAVE 기대 1 · 받은 감사 id 0");
        assertThat(session.report().toMarkdown()).contains("| CLOSE_WAVE | 1 | 0 | ✗ |");
    }

    /** 부족 한 대 · 라우트 셋의 계획까지 — 재배정을 끝낸 세션. */
    private PeakFleet.Session planned() throws InterruptedException {
        openWave();
        ops.waveAnswers.add(List.of(wave(CUTOFF, "PLANNED", 3, CLOSED)));
        ops.feasibility = waveId -> assessment(line("일반", "SHORTFALL", 1, van("seed", true)));
        ops.threeRoutes();
        PeakFleet.Session session = fleet(AT_2358).open(Fleet.FEASIBLE);
        session.provision(CUTOFF);
        session.awaitPlans();
        session.reassign();
        return session;
    }

    @Test
    void as_is_는_재고_리포트하되_더하지_않는다() throws InterruptedException {
        openWave();
        plannedAfter(CLOSED);
        ops.feasibility = waveId -> assessment(line("일반", "SHORTFALL", 5, van("seed", true)),
                line("냉장", "NO_TEMPLATE", null, null));
        PeakFleet.Session session = fleet(AT_2358).open(Fleet.AS_IS);

        session.provision(CUTOFF);
        session.awaitPlans();
        session.reassign();

        assertThat(ops.addedBodies).isEmpty();
        assertThat(ops.commands).as("as-is 는 운영자가 아무것도 하지 않는다 — 웨이브는 스케줄러가 닫는다").isEmpty();
        FleetReport report = session.report();
        assertThat(report.isSuccess()).as("템플릿 없음도 as-is 에서는 보고일 뿐이다").isTrue();
        assertThat(report.toMarkdown()).contains("| 일반 | SHORTFALL | 5 | 0 |", "| 냉장 | NO_TEMPLATE | 모름 | 0 |",
                "— 더한 차량이 없다");
    }

    @Test
    void 부족한데_템플릿이_없는_조합이_하나라도_있으면_하나도_더하지_않고_실패한다() {
        openWave();
        ops.feasibility = waveId -> assessment(line("냉장∧위험물", "NO_TEMPLATE", null, null),
                line("일반", "SHORTFALL", 3, van("seed", true)));
        PeakFleet.Session session = fleet(AT_2358).open(Fleet.FEASIBLE);

        assertThatThrownBy(() -> session.provision(CUTOFF)).isInstanceOf(FleetFailure.class)
                .hasMessageContaining("템플릿 없음").hasMessageContaining("냉장∧위험물");
        assertThat(ops.addedBodies).as("조용히 건너뛰지도, 일부만 더하지도 않는다").isEmpty();
        assertThat(session.report().isSuccess()).isFalse();
    }

    @Test
    void 시작_전_활성_peak_sim_이_있으면_실패한다_비활성인_것과_다른_출처는_세지_않는다() {
        ops.vehicles.put(FakeOpsClient.CAMP, List.of(van("peak-sim", false), van("operator", true)));
        fleet(AT_2358).open(Fleet.AS_IS).requireNoLeftovers();

        ops.vehicles.put(FakeOpsClient.CAMP, List.of(van("peak-sim", true)));
        assertThatThrownBy(() -> fleet(AT_2358).open(Fleet.AS_IS).requireNoLeftovers())
                .isInstanceOf(FleetFailure.class).hasMessageContaining("활성 peak-sim 차량 1대");
    }

    @Test
    void 증차_직전에도_창의_캠프로_전제를_다시_센다() {
        openWave();
        ops.feasibility = waveId -> assessment(line("일반", "SHORTFALL", 1, van("seed", true)));
        ops.vehicles.put(FakeOpsClient.CAMP, List.of(van("peak-sim", true)));

        assertThatThrownBy(() -> fleet(AT_2358).open(Fleet.FEASIBLE).provision(CUTOFF))
                .isInstanceOf(FleetFailure.class).hasMessageContaining("증차 직전");
        assertThat(ops.addedBodies).isEmpty();
    }

    @Test
    void 창의_DAWN_웨이브가_없으면_빈_집합으로_통과하지_않는다() {
        ops.waveAnswers.add(List.of(new OpsClient.Wave(FakeOpsClient.WAVE, "SAME_DAY", CUTOFF, "OPEN", 1, null, null,
                null)));

        assertThatThrownBy(() -> fleet(AT_2358).open(Fleet.FEASIBLE).provision(CUTOFF))
                .isInstanceOf(FleetFailure.class).hasMessageContaining("창의 DAWN 웨이브");
    }

    @Test
    void 증차_전에_계획이_이미_발행됐으면_시간_예산_실패다() {
        openWave();
        ops.feasibility = waveId -> {
            throw new OpsClient.OpsException("함대 판정", 409, "wave-already-planned");
        };

        assertThatThrownBy(() -> fleet(AT_2358).open(Fleet.FEASIBLE).provision(CUTOFF))
                .isInstanceOf(FleetFailure.class).hasMessageContaining("시간 예산");
    }

    @Test
    void 증차가_마감_뒤에_끝났으면_실행을_이어_가지_않고_실패한다() {
        openWave();
        // 마감 00:01:30 인데 증차는 00:02:00 에 끝났다.
        plannedAfter(CLOSED);
        ops.feasibility = waveId -> assessment(line("일반", "SHORTFALL", 1, van("seed", true)));
        PeakFleet.Session session = fleet(Clock.fixed(CLOSED.plusSeconds(30), ZoneOffset.UTC)).open(Fleet.FEASIBLE);
        session.provision(CUTOFF);

        assertThatThrownBy(session::awaitPlans).isInstanceOf(FleetFailure.class)
                .hasMessageContaining("시간 예산").hasMessageContaining("못 봤을 수 있다");
        assertThat(session.planned()).as("계획은 끝났다 — 정리(기사 · 비활성화)는 돈다").isTrue();
        assertThat(session.report().toMarkdown()).contains("✗");
    }

    @Test
    void 마감_시각을_모르면_시간_예산을_말할_수_없어_실패한다() {
        openWave();
        plannedAfter(null);
        ops.feasibility = waveId -> assessment(line("일반", "SHORTFALL", 1, van("seed", true)));
        PeakFleet.Session session = fleet(AT_2358).open(Fleet.FEASIBLE);
        session.provision(CUTOFF);

        assertThatThrownBy(session::awaitPlans).isInstanceOf(FleetFailure.class).hasMessageContaining("closed_at");
    }

    @Test
    void 계획_실패는_실패다() {
        openWave();
        ops.waveAnswers.add(List.of(wave(CUTOFF, "PLAN_FAILED", null, CLOSED)));
        ops.feasibility = waveId -> assessment(line("일반", "FEASIBLE", 0, van("seed", true)));
        PeakFleet.Session session = fleet(AT_2358).open(Fleet.AS_IS);
        session.provision(CUTOFF);

        assertThatThrownBy(session::awaitPlans).isInstanceOf(FleetFailure.class).hasMessageContaining("계획 실패");
        assertThat(session.planned()).isFalse();
    }

    @Test
    void 계획이_상한_안에_끝나지_않으면_실패한다() {
        openWave();
        ops.feasibility = waveId -> assessment(line("일반", "FEASIBLE", 0, van("seed", true)));
        PeakFleet.Session session = fleet(AT_2358).open(Fleet.AS_IS);
        session.provision(CUTOFF);

        assertThatThrownBy(session::awaitPlans).isInstanceOf(FleetFailure.class).hasMessageContaining("60초");
        assertThat(slept[0]).isEqualTo(Duration.ofSeconds(60).toNanos());
    }

    @Test
    void 비활성화의_409_는_상한_안에서_다시_시도한다() throws InterruptedException {
        openWave();
        plannedAfter(CLOSED);
        ops.feasibility = waveId -> assessment(line("일반", "SHORTFALL", 1, van("seed", true)));
        PeakFleet.Session session = fleet(AT_2358).open(Fleet.FEASIBLE);
        session.provision(CUTOFF);
        session.awaitPlans();
        ops.deactivateStatus.put(ops.deactivateCallsTarget(), new ArrayDeque<>(List.of(409, 409)));

        session.release();

        assertThat(ops.deactivateCalls).hasSize(3);
        assertThat(session.report().deactivated()).isEqualTo(1);
    }

    @Test
    void 비활성화가_상한을_넘으면_실패하고_남은_차량을_말한다() throws InterruptedException {
        openWave();
        plannedAfter(CLOSED);
        ops.feasibility = waveId -> assessment(line("일반", "SHORTFALL", 1, van("seed", true)));
        PeakFleet.Session session = fleet(AT_2358).open(Fleet.FEASIBLE);
        session.provision(CUTOFF);
        session.awaitPlans();
        ops.deactivateStatus.put(ops.deactivateCallsTarget(),
                new ArrayDeque<>(List.of(409, 409, 409, 409, 409, 409, 409)));

        assertThatThrownBy(session::release).isInstanceOf(FleetFailure.class)
                .hasMessageContaining("20초 안에 1대").hasMessageContaining("다음 실행의 전제가 잡는다");
    }

    @Test
    void 미배정이_0_5퍼센트를_넘으면_표에_가위표를_남기되_실패가_아니다() throws InterruptedException {
        openWave();
        ops.waveAnswers.add(List.of(new OpsClient.Wave(FakeOpsClient.WAVE, "DAWN", CUTOFF, "PLANNED", 1_000, 6, 3,
                CLOSED)));
        ops.feasibility = waveId -> assessment(line("일반", "FEASIBLE", 0, van("seed", true)));
        PeakFleet.Session session = fleet(AT_2358).open(Fleet.AS_IS);
        session.provision(CUTOFF);
        session.awaitPlans();

        FleetReport report = session.report();
        assertThat(report.isSuccess()).as("기준에 대한 발견이지 도구의 결함이 아니다(ADR-067 결정 8)").isTrue();
        assertThat(report.toMarkdown()).contains("6 / 1000 = 0.60% — §6.7 ≤ 0.5% ✗");
    }
}
