package com.dawnline.sim.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.sim.config.SimProperties.Scenario.Cancel;
import com.dawnline.sim.order.CancelPlan.When;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 어느 주문을 언제 취소하는가 — seed 가 정한다 (7-4 turbulent). */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CancelPlanTest {

    private static final Cancel CANCEL = new Cancel(0.2, 0.5, 20);

    private static GeneratedOrder order(String tier) {
        return new GeneratedOrder(UUID.randomUUID(), tier, "서울", "06236",
                new GeneratedOrder.Parcel(1000, 1000, false, false), List.of(new GeneratedOrder.Item("SKU", 1)));
    }

    private static List<When> decide(List<String> tiers, long seed) {
        CancelPlan plan = new CancelPlan(CANCEL, RandomGeneratorFactory.of("L64X128MixRandom").create(seed));
        List<When> decisions = new ArrayList<>();
        tiers.forEach(tier -> decisions.add(plan.next(order(tier))));
        return decisions;
    }

    @Test
    void 같은_seed_는_같은_취소를_낸다() {
        List<String> tiers = java.util.Collections.nCopies(2000, "DAWN");

        assertThat(decide(tiers, 7L)).isEqualTo(decide(tiers, 7L));
        List<When> decisions = decide(tiers, 7L);
        long cancelled = decisions.stream().filter(when -> when != When.NONE).count();
        assertThat(cancelled).as("비율 0.2 부근").isBetween(340L, 460L);
        assertThat(decisions).contains(When.BEFORE_PLAN, When.AFTER_PUBLISH);
    }

    @Test
    void DAWN_이_아닌_주문은_취소하지_않고_뒤의_결정도_바꾸지_않는다() {
        // 뽑는 수가 티어에 따라 달라지면 한 주문의 티어가 뒤의 모든 결정을 바꾼다 — 주문마다 두 번, 언제나.
        List<String> allDawn = new ArrayList<>(java.util.Collections.nCopies(500, "DAWN"));
        List<String> someOther = new ArrayList<>(allDawn);
        for (int i = 0; i < someOther.size(); i += 3) {
            someOther.set(i, "NEXT_DAY");
        }

        List<When> base = decide(allDawn, 11L);
        List<When> mixed = decide(someOther, 11L);

        for (int i = 0; i < mixed.size(); i++) {
            if (someOther.get(i).equals("DAWN")) {
                assertThat(mixed.get(i)).as("주문 %d", i).isEqualTo(base.get(i));
            } else {
                assertThat(mixed.get(i)).as("주문 %d", i).isEqualTo(When.NONE);
            }
        }
    }
}
