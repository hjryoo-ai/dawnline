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
        plannedAfter(CLOSED);
        ops.feasibility = waveId -> assessment(line("냉장∧위험물", "FEASIBLE", 0, null),
                line("일반", "SHORTFALL", 3, van("seed", true)));
        PeakFleet.Session session = fleet(AT_2358).open(Fleet.FEASIBLE);

        session.provision(CUTOFF);
        session.awaitPlans();
        session.release();

        assertThat(ops.addedBodies).hasSize(3).allSatisfy(body -> {
            assertThat(body.source()).isEqualTo("peak-sim");
            assertThat(body.type()).as("템플릿의 사본").isEqualTo("VAN");
            assertThat(body.shiftStart()).isEqualTo(van("seed", true).shiftStart());
            assertThat(body.code()).startsWith("PSA1B2C3-").hasSizeLessThanOrEqualTo(16);
        });
        assertThat(ops.addedBodies).extracting(OpsClient.NewVehicle::code).doesNotHaveDuplicates();
        assertThat(ops.deactivateCalls).as("더한 것 셋만").hasSize(3);
        assertThat(session.routes()).isEqualTo(12);
        FleetReport report = session.report();
        assertThat(report.isSuccess()).isTrue();
        assertThat(report.added()).isEqualTo(3);
        assertThat(report.deactivated()).isEqualTo(3);
        assertThat(report.auditRows()).as("등록 셋 + 비활성화 셋").isEqualTo(6);
        assertThat(report.toMarkdown()).contains("| 일반 | SHORTFALL | 3 | 3 |", "증차 완료 23:58:00 KST",
                "가장 이른 마감 00:01:30 KST ✅", "9 / 4500 = 0.20% — §6.7 ≤ 0.5% ✅");
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

        assertThat(ops.addedBodies).isEmpty();
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
