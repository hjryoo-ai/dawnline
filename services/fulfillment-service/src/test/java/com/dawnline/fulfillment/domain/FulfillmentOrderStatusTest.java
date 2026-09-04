package com.dawnline.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 주문 상태의 전이표와 <strong>축 규칙</strong> (ADR-017 · ADR-022).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FulfillmentOrderStatusTest {

    private static final Map<FulfillmentOrderStatus, Set<FulfillmentOrderStatus>> EXPECTED = Map.of(
            FulfillmentOrderStatus.PLANNED, Set.of(FulfillmentOrderStatus.CANCELLED),
            FulfillmentOrderStatus.UNSERVICEABLE, Set.of(FulfillmentOrderStatus.CANCELLED),
            FulfillmentOrderStatus.CANCELLED, Set.of());

    @Test
    void 전이표_9개_조합이_ADR_022_와_같다() {
        List<String> mismatches = new ArrayList<>();
        for (FulfillmentOrderStatus from : FulfillmentOrderStatus.values()) {
            for (FulfillmentOrderStatus to : FulfillmentOrderStatus.values()) {
                boolean expected = EXPECTED.get(from).contains(to);
                if (from.canTransitionTo(to) != expected) {
                    mismatches.add("%s → %s (기대 %s)".formatted(from, to, expected));
                }
            }
        }
        assertThat(mismatches).isEmpty();
    }

    @Test
    void 진행_축은_판정_1_취소_2_다() {
        assertThat(FulfillmentOrderStatus.PLANNED.progress()).isEqualTo(1);
        assertThat(FulfillmentOrderStatus.UNSERVICEABLE.progress()).isEqualTo(1);
        assertThat(FulfillmentOrderStatus.CANCELLED.progress()).isEqualTo(2);
    }

    @Test
    void 취소_선착은_축_규칙의_한_사례다() {
        // 취소가 먼저 와서 CANCELLED(2) 인 상태에서 order.placed 가 요구하는 PLANNED(1) 는
        // 이미 지나온 지점이다. 그래서 무시된다 — 별도의 마커 테이블이 필요 없는 이유다.
        assertThat(FulfillmentOrderStatus.CANCELLED
                .hasProgressedPast(FulfillmentOrderStatus.PLANNED)).isTrue();
        assertThat(FulfillmentOrderStatus.CANCELLED
                .hasProgressedPast(FulfillmentOrderStatus.UNSERVICEABLE)).isTrue();
    }

    @Test
    void 판정끼리는_서로를_덮어쓰지_않는다() {
        // PLANNED 와 UNSERVICEABLE 은 같은 단계(1)다. 둘 다 order.placed 를 처리한 결과이고,
        // 하나가 다른 하나로 바뀔 일이 없다.
        assertThat(FulfillmentOrderStatus.PLANNED
                .hasProgressedPast(FulfillmentOrderStatus.UNSERVICEABLE)).isTrue();
        assertThat(FulfillmentOrderStatus.UNSERVICEABLE
                .hasProgressedPast(FulfillmentOrderStatus.PLANNED)).isTrue();
    }

    @Test
    void 판정_상태에서_취소는_앞으로_가는_전이다() {
        assertThat(FulfillmentOrderStatus.PLANNED
                .hasProgressedPast(FulfillmentOrderStatus.CANCELLED)).isFalse();
        assertThat(FulfillmentOrderStatus.UNSERVICEABLE
                .hasProgressedPast(FulfillmentOrderStatus.CANCELLED)).isFalse();
    }

    @Test
    void 취소만_종료_상태다() {
        assertThat(FulfillmentOrderStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(FulfillmentOrderStatus.PLANNED.isTerminal()).isFalse();
        assertThat(FulfillmentOrderStatus.UNSERVICEABLE.isTerminal()).isFalse();
    }

    @Test
    void 웨이브_없이_정리할_수_있는_상태는_둘이다() {
        // ADR-023 — PLANNED 는 소속 웨이브의 상태를 함께 봐야 한다.
        assertThat(FulfillmentOrderStatus.CANCELLED.isSettledWithoutWave()).isTrue();
        assertThat(FulfillmentOrderStatus.UNSERVICEABLE.isSettledWithoutWave()).isTrue();
        assertThat(FulfillmentOrderStatus.PLANNED.isSettledWithoutWave()).isFalse();
    }
}
