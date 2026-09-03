package com.dawnline.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.error.IllegalStateTransitionException;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.order.application.port.in.OrderView;
import com.dawnline.order.application.port.out.OrderEvents;
import com.dawnline.order.application.port.out.OrderRepository;
import com.dawnline.order.domain.DeliveryAddress;
import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderItem;
import com.dawnline.order.domain.OrderStatus;
import com.dawnline.order.domain.Parcel;
import com.dawnline.order.domain.PromisedWindow;
import com.dawnline.order.domain.ServiceTier;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 취소 (DESIGN.md §5.1).
 *
 * <p>취소 가능 여부는 상태 머신이 판정한다(불변규칙 6). 그래서 여기서 확인하는 것은 <em>유스케이스가
 * 그 판정을 우회하지 않는가</em>와 <em>이벤트에 취소 <b>직전</b> 상태가 실리는가</em>다.
 * 후자는 이 메서드가 불릴 때 애그리거트가 이미 {@code CANCELLED} 라서 틀리기 쉽다.
 */
@DisplayName("CancelOrderService — 상태 전이와 이벤트")
class CancelOrderServiceTest {

    private static final Instant PLACED_AT = Instant.parse("2026-09-03T00:00:00Z");
    private static final Instant CANCELLED_AT = Instant.parse("2026-09-03T01:00:00Z");

    private OrderRepository orders;
    private OrderEvents events;
    private CancelOrderService service;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        events = mock(OrderEvents.class);
        service = new CancelOrderService(orders, events, Clock.fixed(CANCELLED_AT, ZoneOffset.UTC));
    }

    private static Order order(OrderStatus status) {
        Order order = Order.place(Ids.newId(), Ids.newId(), ServiceTier.DAWN,
                DeliveryAddress.of("서울 강남구 테헤란로 1", "06236", GeoPoint.of(37.4979, 127.0276)),
                PromisedWindow.of(PLACED_AT.plus(Duration.ofHours(14)), PLACED_AT.plus(Duration.ofHours(21)),
                        ServiceTier.DAWN),
                new Parcel(1200, 8000, false, false),
                List.of(new OrderItem((short) 1, "SKU-1", 1)), PLACED_AT);
        if (status == OrderStatus.PLANNED) {
            order.markPlanned(PLACED_AT.plusSeconds(60));
        } else if (status == OrderStatus.DISPATCHED) {
            order.markPlanned(PLACED_AT.plusSeconds(60));
            order.markDispatched(PLACED_AT.plusSeconds(120));
        }
        return order;
    }

    @Test
    void 취소하면_상태와_시각이_바뀌고_저장된다() {
        Order order = order(OrderStatus.PLACED);
        when(orders.findById(order.id())).thenReturn(Optional.of(order));

        OrderView view = service.cancel(order.id(), "고객 요청");

        assertThat(view.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(view.updatedAt()).isEqualTo(CANCELLED_AT);
        verify(orders).update(order);
    }

    @Test
    void 이벤트에는_취소_직전_상태가_실린다() {
        // 이 메서드가 불릴 때 애그리거트는 이미 CANCELLED 다. order.status() 를 그대로 넘기면
        // 소비자는 항상 CANCELLED 를 보게 되고, PLACED 취소와 PLANNED 취소를 구분하지 못한다.
        Order order = order(OrderStatus.PLANNED);
        when(orders.findById(order.id())).thenReturn(Optional.of(order));

        service.cancel(order.id(), "고객 요청");

        verify(events).cancelled(order, OrderStatus.PLANNED, "고객 요청");
    }

    @Test
    void 저장한_뒤에_이벤트를_기록한다() {
        Order order = order(OrderStatus.PLACED);
        when(orders.findById(order.id())).thenReturn(Optional.of(order));

        service.cancel(order.id(), null);

        InOrder calls = inOrder(orders, events);
        calls.verify(orders).update(order);
        calls.verify(events).cancelled(order, OrderStatus.PLACED, null);
    }

    @Test
    void 사유가_없어도_취소는_성립한다() {
        Order order = order(OrderStatus.PLACED);
        when(orders.findById(order.id())).thenReturn(Optional.of(order));

        assertThat(service.cancel(order.id(), null).status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void 배송_시작_후에는_취소할_수_없고_아무것도_쓰지_않는다() {
        Order order = order(OrderStatus.DISPATCHED);
        when(orders.findById(order.id())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancel(order.id(), null))
                .isInstanceOf(IllegalStateTransitionException.class);

        verify(orders, never()).update(order);
        verify(events, never()).cancelled(order, OrderStatus.DISPATCHED, null);
    }

    @Test
    void 없는_주문은_404_다() {
        UUID missing = Ids.newId();
        when(orders.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(missing, null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void cancel_에_Transactional_이_붙어_있다() throws NoSuchMethodException {
        // 상태 전이와 outbox 기록이 한 트랜잭션이어야 한다(불변규칙 1). ArchUnit 규칙 5 는 위치만 보고
        // 어노테이션이 사라지는 것은 잡지 못한다.
        Method cancel = CancelOrderService.class.getMethod("cancel", UUID.class, String.class);

        assertThat(cancel.isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    void null_인자는_거부한다() {
        assertThatThrownBy(() -> service.cancel(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CancelOrderService(null, events, Clock.systemUTC()))
                .isInstanceOf(NullPointerException.class);
    }
}
