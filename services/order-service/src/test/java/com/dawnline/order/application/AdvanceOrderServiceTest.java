package com.dawnline.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.order.application.port.in.OrderProgress;
import com.dawnline.order.application.port.out.OrderRepository;
import com.dawnline.order.domain.DeliveryAddress;
import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderItem;
import com.dawnline.order.domain.OrderStatus;
import com.dawnline.order.domain.Parcel;
import com.dawnline.order.domain.PromisedWindow;
import com.dawnline.order.domain.ServiceTier;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.annotation.Transactional;

/**
 * 순서 뒤바뀜 흡수 (DESIGN.md §5.1, ADR-017).
 *
 * <p>여기서 지키려는 것은 <strong>판정 순서</strong>다. "철 지났는가" 를 "전이표에 있는가" 보다
 * 먼저 봐야 한다 — {@code DELIVERED} 인데 {@code DISPATCHED} 가 오는 것은 표에도 없지만
 * 잘못된 상황이 아니라 순서 뒤바뀜이다. 순서를 뒤집으면 정상 배송이 알림에 올라간다.
 */
@DisplayName("AdvanceOrderService — 적용 / 철 지남 / 거부")
class AdvanceOrderServiceTest {

    private static final Instant PLACED_AT = Instant.parse("2026-09-03T00:00:00Z");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-03T05:00:00Z");

    private OrderRepository orders;
    private AdvanceOrderService service;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        service = new AdvanceOrderService(orders);
    }

    private Order given(OrderStatus status) {
        Order order = Order.place(Ids.newId(), Ids.newId(), ServiceTier.DAWN,
                DeliveryAddress.of("서울 강남구 테헤란로 1", "06236", GeoPoint.of(37.4979, 127.0276)),
                PromisedWindow.of(PLACED_AT.plus(Duration.ofHours(15)), PLACED_AT.plus(Duration.ofHours(22)),
                        ServiceTier.DAWN),
                new Parcel(1200, 8000, false, false),
                List.of(new OrderItem((short) 1, "SKU-1", 1)), PLACED_AT);
        switch (status) {
            case PLACED -> { }
            case PLANNED -> order.markPlanned(PLACED_AT.plusSeconds(60));
            case DISPATCHED -> {
                order.markPlanned(PLACED_AT.plusSeconds(60));
                order.markDispatched(PLACED_AT.plusSeconds(120));
            }
            case DELIVERED -> order.markDelivered(PLACED_AT.plusSeconds(300));
            case FAILED -> order.markFailed(PLACED_AT.plusSeconds(300));
            case CANCELLED -> order.cancel(PLACED_AT.plusSeconds(30));
        }
        when(orders.findById(order.id())).thenReturn(Optional.of(order));
        return order;
    }

    @Test
    void 정상_경로는_적용된다() {
        Order order = given(OrderStatus.PLANNED);

        assertThat(service.advance(order.id(), OrderStatus.DISPATCHED, OCCURRED_AT))
                .isEqualTo(OrderProgress.APPLIED);
        assertThat(order.status()).isEqualTo(OrderStatus.DISPATCHED);
        assertThat(order.updatedAt()).isEqualTo(OCCURRED_AT);
        verify(orders).update(order);
    }

    @Test
    void 전이_시각은_사건_시각이지_처리_시각이_아니다() {
        // 정시율(§8.1)이 약속창과 이 시각을 비교한다. 처리 시각을 쓰면 지연 배달이 지표 왜곡이 된다.
        Order order = given(OrderStatus.DISPATCHED);

        service.advance(order.id(), OrderStatus.DELIVERED, OCCURRED_AT);

        assertThat(order.updatedAt()).isEqualTo(OCCURRED_AT);
    }

    @Test
    void 배송_완료가_먼저_와도_적용된다() {
        Order order = given(OrderStatus.PLANNED);

        assertThat(service.advance(order.id(), OrderStatus.DELIVERED, OCCURRED_AT))
                .isEqualTo(OrderProgress.APPLIED);
        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    void 계획보다_먼저_와도_적용된다() {
        // fulfillment.planned 가 늦은 경우. 세 이벤트가 모두 다른 토픽이라 가능하다 (§4.5).
        Order order = given(OrderStatus.PLACED);

        assertThat(service.advance(order.id(), OrderStatus.DISPATCHED, OCCURRED_AT))
                .isEqualTo(OrderProgress.APPLIED);
        assertThat(order.status()).isEqualTo(OrderStatus.DISPATCHED);
    }

    @Test
    void 뒤늦게_온_배송_시작은_철_지난_것이고_아무것도_쓰지_않는다() {
        Order order = given(OrderStatus.DELIVERED);

        assertThat(service.advance(order.id(), OrderStatus.DISPATCHED, OCCURRED_AT))
                .isEqualTo(OrderProgress.STALE);
        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
        verify(orders, never()).update(order);
    }

    @Test
    void 같은_상태로의_전이도_철_지난_것이다() {
        // 중복 이벤트는 processed_events 가 앞단에서 거르지만(불변규칙 2), 다른 토픽에서 같은 사실이
        // 두 번 오는 경우까지 막지는 못한다.
        Order order = given(OrderStatus.DISPATCHED);

        assertThat(service.advance(order.id(), OrderStatus.DISPATCHED, OCCURRED_AT))
                .isEqualTo(OrderProgress.STALE);
    }

    @Test
    void 취소된_주문의_배송_이벤트는_거부다() {
        // 철 지난 것으로 조용히 버리면 안 된다 — 취소된 주문의 소포가 차에 실려 있다는 뜻이고
        // 누군가 회수해야 한다 (ADR-017 §3).
        Order order = given(OrderStatus.CANCELLED);

        assertThat(service.advance(order.id(), OrderStatus.DISPATCHED, OCCURRED_AT))
                .isEqualTo(OrderProgress.TRANSITION_NOT_ALLOWED);
        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
        verify(orders, never()).update(order);
    }

    @Test
    void 모르는_주문은_거부다() {
        UUID unknown = Ids.newId();
        when(orders.findById(unknown)).thenReturn(Optional.empty());

        assertThat(service.advance(unknown, OrderStatus.DELIVERED, OCCURRED_AT))
                .isEqualTo(OrderProgress.ORDER_NOT_FOUND);
    }

    @Test
    void 거부_결과만_사람이_봐야_하는_것이다() {
        assertThat(OrderProgress.APPLIED.isRejected()).isFalse();
        assertThat(OrderProgress.STALE.isRejected()).isFalse();
        assertThat(OrderProgress.ORDER_NOT_FOUND.isRejected()).isTrue();
        assertThat(OrderProgress.TRANSITION_NOT_ALLOWED.isRejected()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PLACED", "PLANNED", "CANCELLED"})
    void 이_유스케이스가_다루지_않는_목표_상태는_프로그래밍_오류다(OrderStatus unsupported) {
        // 이벤트 문제가 아니라 리스너가 잘못 부른 것이다. 거부로 삼키면 그 버그가 메트릭에 묻힌다.
        assertThatThrownBy(() -> service.advance(Ids.newId(), unsupported, OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(unsupported.name());
    }

    @Test
    void advance_에_Transactional_이_붙어_있다() throws NoSuchMethodException {
        // processed_events 기록과 상태 변경이 함께 커밋되어야 한다(불변규칙 2).
        Method advance = AdvanceOrderService.class
                .getMethod("advance", UUID.class, OrderStatus.class, Instant.class);

        assertThat(advance.isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    void null_인자는_거부한다() {
        assertThatThrownBy(() -> service.advance(null, OrderStatus.DELIVERED, OCCURRED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.advance(Ids.newId(), null, OCCURRED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.advance(Ids.newId(), OrderStatus.DELIVERED, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AdvanceOrderService(null)).isInstanceOf(NullPointerException.class);
    }
}
