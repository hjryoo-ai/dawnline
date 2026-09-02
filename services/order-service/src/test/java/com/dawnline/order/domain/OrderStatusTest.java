package com.dawnline.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 상태 전이표 전체 (DESIGN.md §5.1 상태 머신).
 *
 * <p>설계서의 다이어그램을 표로 옮겨 놓고 <strong>모든 (현재, 다음) 조합</strong>을 훑는다.
 * 6 × 6 = 36가지다. 허용된 것만 확인하면 "실수로 열려 버린 전이" 는 영원히 안 보인다 —
 * 예컨대 {@code DELIVERED → CANCELLED} 가 열려도 그것만 검사하지 않으면 통과한다.
 */
@DisplayName("OrderStatus — §5.1 상태 전이표")
class OrderStatusTest {

    /** DESIGN.md §5.1 다이어그램을 그대로 옮긴 표. 이 테스트의 명세다. */
    private static final Map<OrderStatus, Set<OrderStatus>> EXPECTED = new EnumMap<>(Map.of(
            OrderStatus.PLACED, EnumSet.of(OrderStatus.PLANNED, OrderStatus.CANCELLED),
            OrderStatus.PLANNED, EnumSet.of(OrderStatus.DISPATCHED, OrderStatus.CANCELLED),
            OrderStatus.DISPATCHED, EnumSet.of(OrderStatus.DELIVERED, OrderStatus.FAILED),
            OrderStatus.DELIVERED, EnumSet.noneOf(OrderStatus.class),
            OrderStatus.FAILED, EnumSet.noneOf(OrderStatus.class),
            OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class)));

    @Test
    void 전이표는_모든_상태를_빠짐없이_덮는다() {
        // 상태가 새로 생기면 이 테스트가 먼저 깨진다. 명세를 갱신하지 않고는 통과할 수 없다.
        assertThat(EXPECTED.keySet()).containsExactlyInAnyOrder(OrderStatus.values());
    }

    @Test
    void 서른여섯_가지_조합_전부가_설계서_표와_같다() {
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                boolean expected = EXPECTED.get(from).contains(to);
                assertThat(from.canTransitionTo(to))
                        .as("%s → %s", from, to)
                        .isEqualTo(expected);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void 같은_상태로의_전이는_어디서도_허용되지_않는다(OrderStatus status) {
        // 중복 이벤트는 processed_events 가 리스너 앞단에서 거른다 (불변규칙 2).
        // 여기서 조용히 통과시키면 진짜 잘못된 전이까지 함께 숨는다.
        assertThat(status.canTransitionTo(status)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"DELIVERED", "FAILED", "CANCELLED"})
    void 종료_상태에서는_아무_데도_갈_수_없다(OrderStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        assertThat(terminal.allowedTransitions()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PLACED", "PLANNED", "DISPATCHED"})
    void 진행_중_상태는_종료_상태가_아니다(OrderStatus inFlight) {
        assertThat(inFlight.isTerminal()).isFalse();
    }

    @Test
    void 취소_가능_상태는_PLACED_와_PLANNED_뿐이다() {
        // §5.1: DISPATCHED 이후 취소 불가 → 409.
        assertThat(EnumSet.allOf(OrderStatus.class).stream().filter(OrderStatus::isCancellable).toList())
                .containsExactlyInAnyOrder(OrderStatus.PLACED, OrderStatus.PLANNED);
    }

    @Test
    void 배송_시작_후에는_취소할_수_없다() {
        assertThat(OrderStatus.DISPATCHED.isCancellable()).isFalse();
    }

    @Test
    void allowedTransitions_는_수정할_수_없다() {
        // 호출자가 전이표를 바꿀 수 있으면 그건 더 이상 명세가 아니다.
        assertThat(OrderStatus.PLACED.allowedTransitions())
                .isUnmodifiable();
    }
}
