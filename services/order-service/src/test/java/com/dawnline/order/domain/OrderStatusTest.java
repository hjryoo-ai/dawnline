package com.dawnline.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            // PLACED 에서 DISPATCHED·DELIVERED·FAILED 로 곧바로 가는 것은 fulfillment.planned 가
            // 늦게 도착한 경우다. 소비하는 세 이벤트가 모두 다른 토픽이라 순서가 보장되지 않는다.
            OrderStatus.PLACED, EnumSet.of(OrderStatus.PLANNED, OrderStatus.DISPATCHED,
                    OrderStatus.DELIVERED, OrderStatus.FAILED, OrderStatus.CANCELLED),
            // PLANNED → DELIVERED·FAILED 는 §4.5 순서 뒤바뀜 흡수용이다 (ADR-017).
            OrderStatus.PLANNED, EnumSet.of(OrderStatus.DISPATCHED, OrderStatus.DELIVERED,
                    OrderStatus.FAILED, OrderStatus.CANCELLED),
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

    @Test
    void 진행_단계는_설계서의_순서와_같다() {
        // ADR-017: PLACED(0) → PLANNED(1) → DISPATCHED(2) → DELIVERED·FAILED(3).
        assertThat(OrderStatus.PLACED.progress()).isZero();
        assertThat(OrderStatus.PLANNED.progress()).isEqualTo(1);
        assertThat(OrderStatus.DISPATCHED.progress()).isEqualTo(2);
        assertThat(OrderStatus.DELIVERED.progress()).isEqualTo(3);
        assertThat(OrderStatus.FAILED.progress()).isEqualTo(3);
        // CANCELLED 는 진행 축 밖이다. 취소된 주문의 배송 이벤트는 철 지난 중복이 아니라
        // 실제로 잘못된 상황이라 조용히 버리면 안 된다.
        assertThat(OrderStatus.CANCELLED.progress()).isNegative();
    }

    @Test
    void 전이표와_진행_단계가_서로_모순되지_않는다() {
        // 상태가 늘 때 둘 중 하나만 갱신하는 실수를 막는다.
        // 진행 축 위의 정식 전이는 반드시 앞으로 간다.
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : from.allowedTransitions()) {
                if (from.progress() >= 0 && to.progress() >= 0) {
                    assertThat(to.progress())
                            .as("%s → %s 는 정식 전이인데 진행이 뒤로 간다", from, to)
                            .isGreaterThan(from.progress());
                }
            }
        }
    }

    @Test
    void 전이표는_진행_축_위에서_앞으로_가는_모든_전이를_허용한다() {
        // ADR-017의 규칙을 표 전체에 적용한 것이다: 미래로의 건너뜀은 수용, 과거로의 역행은 stale.
        // 표를 손으로 적는 이상 규칙과 표가 어긋날 수 있으므로, 규칙에서 표를 만들어 대조한다.
        for (OrderStatus from : OrderStatus.values()) {
            if (from.progress() < 0) {
                continue;               // CANCELLED 는 축 밖이다
            }
            Set<OrderStatus> forward = EnumSet.noneOf(OrderStatus.class);
            for (OrderStatus to : OrderStatus.values()) {
                if (to.progress() > from.progress()) {
                    forward.add(to);
                }
            }
            Set<OrderStatus> actual = EnumSet.noneOf(OrderStatus.class);
            actual.addAll(from.allowedTransitions());
            actual.remove(OrderStatus.CANCELLED);   // 취소는 이벤트가 아니라 명령이라 축 밖이다

            assertThat(actual)
                    .as("%s 에서 앞으로 가는 전이", from)
                    .isEqualTo(forward);
        }
    }

    @Test
    void 취소는_배송이_시작되기_전까지만_가능하다() {
        // 취소는 진행 축의 규칙이 아니라 별도 제약이다. 축 규칙만 있으면 DISPATCHED 에서도
        // 취소가 열려 버린다 — 소포가 이미 차에 실린 뒤다.
        for (OrderStatus from : OrderStatus.values()) {
            assertThat(from.canTransitionTo(OrderStatus.CANCELLED))
                    .as("%s → CANCELLED", from)
                    .isEqualTo(from == OrderStatus.PLACED || from == OrderStatus.PLANNED);
        }
    }

    @Test
    void 뒤늦게_도착한_배송_시작은_철_지난_것으로_판정된다() {
        // 주문이 이미 DELIVERED 인데 order.dispatched 가 도착한 경우 (ADR-017).
        assertThat(OrderStatus.DELIVERED.hasProgressedPast(OrderStatus.DISPATCHED)).isTrue();
        assertThat(OrderStatus.DISPATCHED.hasProgressedPast(OrderStatus.DISPATCHED)).isTrue();
        assertThat(OrderStatus.FAILED.hasProgressedPast(OrderStatus.DISPATCHED)).isTrue();
    }

    @Test
    void 아직_오지_않은_지점은_철_지난_것이_아니다() {
        assertThat(OrderStatus.PLANNED.hasProgressedPast(OrderStatus.DISPATCHED)).isFalse();
        assertThat(OrderStatus.PLANNED.hasProgressedPast(OrderStatus.DELIVERED)).isFalse();
        assertThat(OrderStatus.PLACED.hasProgressedPast(OrderStatus.PLANNED)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void 취소는_어느_쪽이든_진행_비교_대상이_아니다(OrderStatus other) {
        // 축이 없으므로 "철 지났다" 는 판정이 성립하지 않는다. 그 결과 취소된 주문에 온 배송
        // 이벤트는 무시가 아니라 rejected 로 올라간다 (§4.6 3행).
        assertThat(OrderStatus.CANCELLED.hasProgressedPast(other)).isFalse();
        assertThat(other.hasProgressedPast(OrderStatus.CANCELLED)).isFalse();
    }

    @Test
    void hasProgressedPast_는_null_을_받지_않는다() {
        assertThatThrownBy(() -> OrderStatus.PLACED.hasProgressedPast(null))
                .isInstanceOf(NullPointerException.class);
    }
}
