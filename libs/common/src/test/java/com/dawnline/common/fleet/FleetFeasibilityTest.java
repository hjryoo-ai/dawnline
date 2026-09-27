package com.dawnline.common.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.fleet.FleetFeasibility.Assessment;
import com.dawnline.common.fleet.FleetFeasibility.Combination;
import com.dawnline.common.fleet.FleetFeasibility.Line;
import com.dawnline.common.fleet.FleetFeasibility.Status;
import com.dawnline.common.fleet.FleetFeasibility.Stop;
import com.dawnline.common.fleet.FleetFeasibility.Vehicle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 함대 실현 가능성 (DESIGN.md §5.3 「함대」, ADR-033 · ADR-067). */
class FleetFeasibilityTest {

    private static final Combination GENERAL = new Combination(false, false, false);
    private static final Combination COLD = new Combination(true, false, false);
    private static final Combination HAZMAT = new Combination(false, true, false);
    private static final Combination COLD_HAZMAT = new Combination(true, true, false);

    /** 시드의 야간조 모양 — 밴(400 kg · 1.2 m³)과 트럭(1,200 kg · 4 m³). */
    private static Vehicle van(String code, boolean cold, boolean hazmat, long cost) {
        return new Vehicle("id-" + code, code, cold, hazmat, 400_000, 1_200_000, cost);
    }

    private static Vehicle truck(String code, boolean cold, boolean hazmat, long cost) {
        return new Vehicle("id-" + code, code, cold, hazmat, 1_200_000, 4_000_000, cost);
    }

    private static List<Stop> stops(int count, boolean cold, boolean hazmat, long weightG) {
        return Collections.nCopies(count, new Stop(cold, hazmat, weightG, 1_000));
    }

    private static List<Stop> concat(List<Stop> first, List<Stop> second) {
        List<Stop> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    @Test
    void 조합은_셋의_곱이고_요구하는_능력이_많은_것부터다() {
        List<Combination> all = Combination.bySpecificity();

        assertThat(all).hasSize(8).doesNotHaveDuplicates();
        assertThat(all.getFirst()).isEqualTo(new Combination(true, true, true));
        assertThat(all.getLast()).isEqualTo(GENERAL);
        for (int i = 1; i < all.size(); i++) {
            assertThat(all.get(i).specificity()).isLessThanOrEqualTo(all.get(i - 1).specificity());
        }
    }

    @Test
    void 여유_안이면_전부_FEASIBLE_이고_더할_것이_없다() {
        Assessment assessment = FleetFeasibility.assess(stops(10, false, false, 1_000),
                List.of(van("V1", false, false, 45_000)), 120);

        assertThat(assessment.feasible()).isTrue();
        assertThat(assessment.lines()).allSatisfy(line -> assertThat(line.shortfall()).isZero());
    }

    @Test
    void 경계_80퍼센트는_포함이고_한_단위_넘으면_부족하다() {
        // 밴 한 대 400,000 g 의 80% = 320,000 g.
        Vehicle van = van("V1", false, false, 45_000);

        Line exact = FleetFeasibility.assess(stops(32, false, false, 10_000), List.of(van), null)
                .line(GENERAL).orElseThrow();
        Line over = FleetFeasibility.assess(concat(stops(32, false, false, 10_000), stops(1, false, false, 1)),
                List.of(van), null).line(GENERAL).orElseThrow();

        assertThat(exact.status()).isEqualTo(Status.FEASIBLE);
        assertThat(over.status()).isEqualTo(Status.SHORTFALL);
        assertThat(over.shortfall()).isEqualTo(1);
    }

    @Test
    void 부족_대수는_세_축_가운데_가장_많이_요구하는_축이_정한다() {
        // 중량은 밴 1대로 충분하지만(100 × 50,000 ≤ 80 × 400,000) stop 은 300 개 — 상한 120 의 80% = 96 개/대 → 4대.
        Assessment assessment = FleetFeasibility.assess(stops(300, false, false, 166),
                List.of(van("V1", false, false, 45_000)), 120);

        Line general = assessment.line(GENERAL).orElseThrow();
        assertThat(general.status()).isEqualTo(Status.SHORTFALL);
        assertThat(general.shortfall()).as("ceil(300 / 96) − 1 = 3").isEqualTo(3);
    }

    @Test
    void stop_상한이_없으면_stop_축을_재지_않는다() {
        // ADR-039 결정 2 — 자리를 세지 않는 축에서는 자리가 희소할 수 없다.
        Assessment assessment = FleetFeasibility.assess(stops(300, false, false, 166),
                List.of(van("V1", false, false, 45_000)), null);

        assertThat(assessment.feasible()).isTrue();
        assertThat(assessment.maxStopsPerRoute()).isNull();
    }

    @Test
    void 부족분은_가장_특정한_조합부터_재고_더한_차량은_덮는_조합의_용량이_된다() {
        // 냉장∧위험물 수요가 트럭 1대를 넘고, 그 트럭이 일반 조합의 용량이기도 하다.
        List<Vehicle> fleet = List.of(truck("T1", true, true, 87_000), van("V1", false, false, 45_000));
        List<Stop> demand = concat(stops(120, true, true, 10_000), stops(40, false, false, 10_000));

        Assessment assessment = FleetFeasibility.assess(demand, fleet, null);

        Line coldHazmat = assessment.line(COLD_HAZMAT).orElseThrow();
        Line general = assessment.line(GENERAL).orElseThrow();
        // 냉장∧위험물: 1,200,000 g 수요 ≤ 0.8 × 1,200,000 × (1 + n) → n = 1 (0.8 × 2,400,000 = 1,920,000)
        assertThat(coldHazmat.shortfall()).isEqualTo(1);
        assertThat(coldHazmat.template().code()).isEqualTo("T1");
        // 일반: 수요 1,600,000 g. 기존 1,600,000 g 의 80% = 1,280,000 로는 모자라지만, 앞에서 더한 트럭까지 2,800,000 → 여유 안
        assertThat(general.capacity().weightG()).isEqualTo(1_600_000);
        assertThat(general.withAdded().weightG()).isEqualTo(2_800_000);
        assertThat(general.status()).isEqualTo(Status.FEASIBLE);
        assertThat(general.shortfall())
                .as("거꾸로(일반부터) 재면 밴을 사고 그 용량을 트럭이 한 번 더 산다 — ADR-039 불변식 2 의 거울")
                .isZero();
    }

    @Test
    void 템플릿이_없으면_NO_TEMPLATE_이고_대수는_값이_아니다() {
        // 위험물 수요가 있는데 위험물 차량이 한 대도 없다.
        Assessment assessment = FleetFeasibility.assess(stops(5, false, true, 1_000),
                List.of(van("V1", false, false, 45_000)), 120);

        Line hazmat = assessment.line(HAZMAT).orElseThrow();
        assertThat(hazmat.status()).isEqualTo(Status.NO_TEMPLATE);
        assertThat(hazmat.shortfall()).isNull();
        assertThat(hazmat.template()).isNull();
        assertThat(hazmat.vehicles()).isZero();
        assertThat(assessment.violations()).containsExactly(hazmat);
    }

    @Test
    void 템플릿은_냉장_위험물이_정확히_같은_가장_싼_차다() {
        // 냉장∧위험물 밴이 더 싸도 냉장 조합의 템플릿이 아니다 — 희소 능력의 자리다(ADR-038).
        List<Vehicle> fleet = List.of(van("V1", true, true, 40_000), truck("T2", true, false, 90_000),
                truck("T1", true, false, 87_000));

        Line cold = FleetFeasibility.assess(stops(200, true, false, 10_000), fleet, null)
                .line(COLD).orElseThrow();

        assertThat(cold.template().code()).isEqualTo("T1");
    }

    @Test
    void 고정비가_같으면_코드_순이다() {
        List<Vehicle> fleet = List.of(van("V2", false, false, 45_000), van("V1", false, false, 45_000));

        Line general = FleetFeasibility.assess(stops(100, false, false, 10_000), fleet, null)
                .line(GENERAL).orElseThrow();

        assertThat(general.template().code()).isEqualTo("V1");
    }

    @Test
    void 크기는_정확히에_들지_않는다_대형이_아닌_냉장_조합의_템플릿이_트럭이어도_된다() {
        // 시드 야간조의 냉장 차량은 전부 트럭(대형)이다. 크기까지 정확히 맞추면 이 조합의 템플릿이 사라진다.
        List<Vehicle> fleet = List.of(van("V1", false, false, 45_000), truck("T1", true, false, 87_000));

        Line cold = FleetFeasibility.assess(stops(200, true, false, 10_000), fleet, null)
                .line(COLD).orElseThrow();

        assertThat(cold.status()).isEqualTo(Status.SHORTFALL);
        assertThat(cold.template().code()).isEqualTo("T1");
    }

    @Test
    void 대형은_가장_작은_차에_들어가지_않는_stop_이고_그것을_넘는_차만_싣는다() {
        List<Vehicle> fleet = List.of(van("V1", false, false, 45_000), truck("T1", false, false, 87_000));
        List<Stop> demand = concat(stops(1, false, false, 500_000), stops(1, false, false, 1_000));

        Assessment assessment = FleetFeasibility.assess(demand, fleet, null);

        Line large = assessment.line(new Combination(false, false, true)).orElseThrow();
        assertThat(large.demand().stops()).as("400 kg 밴에 안 들어가는 stop 하나").isEqualTo(1);
        assertThat(large.vehicles()).as("밴보다 큰 트럭 하나").isEqualTo(1);
        assertThat(assessment.smallest().weightG()).isEqualTo(400_000);
    }

    @Test
    void 함대가_비었으면_수요가_있는_조합은_NO_TEMPLATE_이다() {
        Assessment assessment = FleetFeasibility.assess(stops(1, false, false, 1_000), List.of(), 120);

        assertThat(assessment.line(GENERAL).orElseThrow().status()).isEqualTo(Status.NO_TEMPLATE);
        assertThat(assessment.smallest()).isNull();
    }

    @Test
    void 수요가_없는_조합은_용량이_0이어도_FEASIBLE_이다() {
        Assessment assessment = FleetFeasibility.assess(List.of(), List.of(van("V1", false, false, 45_000)), 120);

        assertThat(assessment.feasible()).isTrue();
    }

    @Test
    void stop_상한은_양수다() {
        assertThatThrownBy(() -> FleetFeasibility.assess(List.of(), List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
