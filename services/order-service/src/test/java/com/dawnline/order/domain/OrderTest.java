package com.dawnline.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.error.IllegalStateTransitionException;
import com.dawnline.common.error.ValidationException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("Order — 주문 애그리거트 (DESIGN.md §5.1)")
class OrderTest {

    private static final Instant PLACED_AT = Instant.parse("2026-09-02T10:00:00Z");
    private static final GeoPoint GANGNAM = GeoPoint.of(37.4979, 127.0276);

    private static Order placed() {
        return Order.place(Ids.newId(), Ids.newId(), ServiceTier.DAWN,
                DeliveryAddress.of("서울 강남구 테헤란로 1", "06236", GANGNAM),
                PromisedWindow.of(PLACED_AT.plus(Duration.ofHours(14)),
                        PLACED_AT.plus(Duration.ofHours(21)), ServiceTier.DAWN),
                new Parcel(1200, 8000, false, false),
                List.of(new OrderItem((short) 1, "SKU-1001", 2)),
                PLACED_AT);
    }

    @Test
    void place_새_주문은_PLACED_로_시작한다() {
        Order order = placed();

        assertThat(order.status()).isEqualTo(OrderStatus.PLACED);
        assertThat(order.placedAt()).isEqualTo(PLACED_AT);
        assertThat(order.updatedAt()).isEqualTo(PLACED_AT);
        assertThat(order.version()).isZero();
    }

    @Test
    void 전이하면_updatedAt_이_전이_시각으로_바뀐다() {
        Order order = placed();
        Instant plannedAt = PLACED_AT.plus(Duration.ofMinutes(3));

        order.markPlanned(plannedAt);

        assertThat(order.status()).isEqualTo(OrderStatus.PLANNED);
        assertThat(order.updatedAt()).isEqualTo(plannedAt);
        // 접수 시각은 전이로 바뀌지 않는다.
        assertThat(order.placedAt()).isEqualTo(PLACED_AT);
    }

    @Test
    void 정상_경로_PLACED_PLANNED_DISPATCHED_DELIVERED() {
        Order order = placed();

        order.markPlanned(PLACED_AT.plusSeconds(60));
        order.markDispatched(PLACED_AT.plusSeconds(120));
        order.markDelivered(PLACED_AT.plusSeconds(180));

        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.status().isTerminal()).isTrue();
    }

    @Test
    void 배송_실패_경로() {
        Order order = placed();
        order.markPlanned(PLACED_AT.plusSeconds(60));
        order.markDispatched(PLACED_AT.plusSeconds(120));

        order.markFailed(PLACED_AT.plusSeconds(180));

        assertThat(order.status()).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    void 배송_완료가_배송_시작보다_먼저_도착해도_전이된다() {
        // order.dispatched(키 orderId)와 delivery.status(키 routeId)는 다른 파티션이라
        // 순서가 보장되지 않는다 (§4.5, ADR-017). 예전 전이표였다면 여기서 409 가 나고
        // 재시도 3회를 소진한 뒤 정상 배송이 DLQ 로 갔다.
        Order order = placed();
        order.markPlanned(PLACED_AT.plusSeconds(60));

        order.markDelivered(PLACED_AT.plusSeconds(3600));

        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    void 배송_실패도_배송_시작보다_먼저_도착할_수_있다() {
        Order order = placed();
        order.markPlanned(PLACED_AT.plusSeconds(60));

        order.markFailed(PLACED_AT.plusSeconds(3600));

        assertThat(order.status()).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    void 뒤늦게_온_배송_시작은_철_지난_것으로_판정된다() {
        // 위 상황의 짝이다. 리스너는 이 판정을 보고 예외 대신 무시를 고른다.
        Order order = placed();
        order.markPlanned(PLACED_AT.plusSeconds(60));
        order.markDelivered(PLACED_AT.plusSeconds(3600));

        assertThat(order.status().hasProgressedPast(OrderStatus.DISPATCHED)).isTrue();
        // 그래도 전이 자체는 여전히 거부된다 — 무시할지 말지는 리스너가 정한다.
        assertThatThrownBy(() -> order.markDispatched(PLACED_AT.plusSeconds(3700)))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void PLACED_에서_취소할_수_있다() {
        Order order = placed();

        order.cancel(PLACED_AT.plusSeconds(10));

        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void PLANNED_에서도_취소할_수_있다() {
        Order order = placed();
        order.markPlanned(PLACED_AT.plusSeconds(60));

        order.cancel(PLACED_AT.plusSeconds(70));

        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void DISPATCHED_이후에는_취소가_거부된다() {
        Order order = placed();
        order.markPlanned(PLACED_AT.plusSeconds(60));
        order.markDispatched(PLACED_AT.plusSeconds(120));

        // §5.1: 배송이 시작된 뒤의 취소는 409 다.
        assertThatThrownBy(() -> order.cancel(PLACED_AT.plusSeconds(130)))
                .isInstanceOf(IllegalStateTransitionException.class)
                .satisfies(e -> assertThat(((IllegalStateTransitionException) e).status()).isEqualTo(409))
                .hasMessageContaining("DISPATCHED")
                .hasMessageContaining("CANCELLED");
    }

    @Test
    void 거부된_전이는_상태를_바꾸지_않는다() {
        // 진행 축 위에서 앞으로 가는 전이는 전부 허용되므로(ADR-017), 거부되는 것은 취소 제약이나
        // 역행뿐이다. 여기서는 배송이 시작된 뒤의 취소를 쓴다.
        Order order = placed();
        order.markPlanned(PLACED_AT.plusSeconds(60));
        order.markDispatched(PLACED_AT.plusSeconds(120));
        Instant before = order.updatedAt();

        assertThatThrownBy(() -> order.cancel(PLACED_AT.plusSeconds(180)))
                .isInstanceOf(IllegalStateTransitionException.class);

        // 실패한 전이가 updatedAt 만 움직여 놓으면, 아무 일도 없었는데 갱신된 것처럼 보인다.
        assertThat(order.status()).isEqualTo(OrderStatus.DISPATCHED);
        assertThat(order.updatedAt()).isEqualTo(before);
    }

    @Test
    void 배송_시작도_배송_완료도_계획보다_먼저_도착할_수_있다() {
        // fulfillment.planned 가 늦으면 주문은 PLACED 인 채로 그 뒤의 이벤트를 먼저 받는다.
        // 세 이벤트가 모두 다른 토픽이라 셋 사이의 순서는 보장되지 않는다 (§4.5, ADR-017).
        Order dispatchedFirst = placed();
        dispatchedFirst.markDispatched(PLACED_AT.plusSeconds(120));
        assertThat(dispatchedFirst.status()).isEqualTo(OrderStatus.DISPATCHED);

        Order deliveredFirst = placed();
        deliveredFirst.markDelivered(PLACED_AT.plusSeconds(300));
        assertThat(deliveredFirst.status()).isEqualTo(OrderStatus.DELIVERED);

        Order failedFirst = placed();
        failedFirst.markFailed(PLACED_AT.plusSeconds(300));
        assertThat(failedFirst.status()).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    void 같은_전이를_두_번_하면_두_번째는_거부된다() {
        Order order = placed();
        order.markPlanned(PLACED_AT.plusSeconds(60));

        assertThatThrownBy(() -> order.markPlanned(PLACED_AT.plusSeconds(61)))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"DELIVERED", "FAILED", "CANCELLED"})
    void 종료_상태에서는_어떤_전이도_거부된다(OrderStatus terminal) {
        List<BiConsumer<Order, Instant>> transitions = List.of(
                Order::markPlanned, Order::markDispatched, Order::markDelivered,
                Order::markFailed, Order::cancel);

        for (BiConsumer<Order, Instant> transition : transitions) {
            Order order = rehydratedAs(terminal);
            assertThatThrownBy(() -> transition.accept(order, PLACED_AT.plusSeconds(999)))
                    .as("%s 에서의 전이", terminal)
                    .isInstanceOf(IllegalStateTransitionException.class);
            assertThat(order.status()).isEqualTo(terminal);
        }
    }

    @Test
    void 전이_메서드는_시각을_반드시_받는다() {
        Order order = placed();

        assertThatThrownBy(() -> order.markPlanned(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 파티션_키는_주문_id_다() {
        Order order = placed();

        // §4.5: 같은 주문의 이벤트는 같은 파티션으로 가야 순서가 보장된다.
        assertThat(order.partitionKey()).isEqualTo(order.id().toString());
    }

    @Test
    void toString_은_주소나_고객_id_를_드러내지_않는다() {
        Order order = placed();

        String rendered = order.toString();

        assertThat(rendered).contains(order.id().toString());
        assertThat(rendered).doesNotContain(order.customerId().toString());
        assertThat(rendered).doesNotContain("테헤란로");
    }

    @Test
    void items_는_수정할_수_없고_생성_후_외부_변경에도_영향받지_않는다() {
        List<OrderItem> mutable = new ArrayList<>(List.of(new OrderItem((short) 1, "SKU-1", 1)));
        Order order = Order.place(Ids.newId(), Ids.newId(), ServiceTier.DAWN,
                DeliveryAddress.of("서울 강남구 테헤란로 1", "06236", GANGNAM),
                PromisedWindow.of(PLACED_AT.plus(Duration.ofHours(14)),
                        PLACED_AT.plus(Duration.ofHours(21)), ServiceTier.DAWN),
                new Parcel(1200, 8000, false, false), mutable, PLACED_AT);

        mutable.add(new OrderItem((short) 2, "SKU-2", 1));

        assertThat(order.items()).hasSize(1);
        assertThat(order.items()).isUnmodifiable();
    }

    @Test
    void 품목이_없으면_거부한다() {
        assertThatThrownBy(() -> Order.place(Ids.newId(), Ids.newId(), ServiceTier.DAWN,
                DeliveryAddress.of("서울 강남구 테헤란로 1", "06236", GANGNAM),
                PromisedWindow.of(PLACED_AT.plus(Duration.ofHours(14)),
                        PLACED_AT.plus(Duration.ofHours(21)), ServiceTier.DAWN),
                new Parcel(1200, 8000, false, false), List.of(), PLACED_AT))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("items");
    }

    @Test
    void 품목_순번이_중복되면_거부한다() {
        // (order_id, line_no) 가 PK 다. DB 까지 가면 트랜잭션이 통째로 중단된다.
        assertThatThrownBy(() -> Order.place(Ids.newId(), Ids.newId(), ServiceTier.DAWN,
                DeliveryAddress.of("서울 강남구 테헤란로 1", "06236", GANGNAM),
                PromisedWindow.of(PLACED_AT.plus(Duration.ofHours(14)),
                        PLACED_AT.plus(Duration.ofHours(21)), ServiceTier.DAWN),
                new Parcel(1200, 8000, false, false),
                List.of(new OrderItem((short) 1, "SKU-1", 1), new OrderItem((short) 1, "SKU-2", 1)),
                PLACED_AT))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("lineNo");
    }

    @Test
    void rehydrate_는_저장된_상태를_그대로_되살린다() {
        Order order = rehydratedAs(OrderStatus.DISPATCHED);

        assertThat(order.status()).isEqualTo(OrderStatus.DISPATCHED);
        assertThat(order.version()).isEqualTo(7L);
        // 되살린 주문도 상태 머신을 그대로 따른다.
        order.markDelivered(PLACED_AT.plusSeconds(300));
        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
    }

    private static Order rehydratedAs(OrderStatus status) {
        UUID id = Ids.newId();
        return Order.rehydrate(id, Ids.newId(), ServiceTier.DAWN,
                DeliveryAddress.of("서울 강남구 테헤란로 1", "06236", GANGNAM),
                PromisedWindow.of(PLACED_AT.plus(Duration.ofHours(14)),
                        PLACED_AT.plus(Duration.ofHours(21)), ServiceTier.DAWN),
                new Parcel(1200, 8000, false, false),
                List.of(new OrderItem((short) 1, "SKU-1001", 2)),
                status, PLACED_AT, PLACED_AT.plusSeconds(200), 7L);
    }
}
