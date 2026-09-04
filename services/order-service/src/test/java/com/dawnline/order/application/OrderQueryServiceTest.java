package com.dawnline.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.error.NotFoundException;
import com.dawnline.order.application.port.in.ListOrdersQuery;
import com.dawnline.order.application.port.in.OrderPage;
import com.dawnline.order.application.port.in.OrderView;
import com.dawnline.order.application.port.out.OrderRepository;
import com.dawnline.order.domain.DeliveryAddress;
import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderItem;
import com.dawnline.order.domain.OrderStatus;
import com.dawnline.order.domain.Parcel;
import com.dawnline.order.domain.PromisedWindow;
import com.dawnline.order.domain.ServiceTier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 주문 조회 (DESIGN.md §5.1).
 *
 * <p>여기서 보는 것은 <strong>커서 판정</strong>이다 — 다음 페이지가 있는지를 어떻게 아는가,
 * 그리고 마지막 페이지에서 커서를 주지 않는가. 틀리면 클라이언트가 무한히 페이지를 넘기거나
 * 마지막 몇 건을 못 본다.
 */
@DisplayName("OrderQueryService — 상세와 커서 페이지")
class OrderQueryServiceTest {

    private static final Instant PLACED_AT = Instant.parse("2026-09-03T00:00:00Z");
    private static final UUID CUSTOMER = Ids.newId();

    private OrderRepository orders;
    private OrderQueryService queries;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        queries = new OrderQueryService(orders);
    }

    private static Order order(Instant placedAt) {
        return Order.place(Ids.newId(), CUSTOMER, ServiceTier.DAWN,
                DeliveryAddress.of("서울 강남구 테헤란로 1", "06236", GeoPoint.of(37.4979, 127.0276)),
                PromisedWindow.of(placedAt.plus(Duration.ofHours(14)), placedAt.plus(Duration.ofHours(21)),
                        ServiceTier.DAWN),
                new Parcel(1200, 8000, false, false),
                List.of(new OrderItem((short) 1, "SKU-1", 1)), placedAt);
    }

    private static List<Order> orders(int count) {
        List<Order> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(order(PLACED_AT.minusSeconds(i)));
        }
        return list;
    }

    private static ListOrdersQuery query(int limit) {
        return new ListOrdersQuery(CUSTOMER, null, null, null, null, null, limit);
    }

    @Test
    void 상세를_읽는다() {
        Order order = order(PLACED_AT);
        when(orders.findById(order.id())).thenReturn(Optional.of(order));

        OrderView view = queries.get(order.id());

        assertThat(view.orderId()).isEqualTo(order.id());
        assertThat(view.status()).isEqualTo(OrderStatus.PLACED);
        assertThat(view.address().geohash7()).isEqualTo(order.address().geohash7());
        assertThat(view.items()).hasSize(1);
    }

    @Test
    void 없는_주문은_404_다() {
        UUID missing = Ids.newId();
        when(orders.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> queries.get(missing)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 다음_페이지를_알려고_한_건을_더_읽는다() {
        // limit + 1 을 읽는 것이 "다음이 있는가" 를 아는 방법이다. 이걸 빠뜨리면 마지막 페이지에서도
        // 커서를 주게 되고 클라이언트가 빈 페이지를 한 번 더 받는다.
        when(orders.findByCustomer(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(orders(3));

        queries.list(query(3));

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(orders)
                .findByCustomer(eq(CUSTOMER), any(), any(), any(), any(), any(), limit.capture());
        assertThat(limit.getValue()).isEqualTo(4);
    }

    @Test
    void 더_있으면_마지막_한_건을_버리고_커서를_준다() {
        List<Order> found = orders(4);
        when(orders.findByCustomer(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(found);

        OrderPage page = queries.list(query(3));

        assertThat(page.orders()).hasSize(3);
        assertThat(page.nextCursor()).isNotNull();
        // 커서는 <버린 것>이 아니라 <돌려준 마지막 것>을 가리킨다. 아니면 한 건을 건너뛴다.
        assertThat(page.nextCursor().orderId()).isEqualTo(found.get(2).id());
        assertThat(page.nextCursor().placedAt()).isEqualTo(found.get(2).placedAt());
    }

    @Test
    void 마지막_페이지에는_커서가_없다() {
        when(orders.findByCustomer(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(orders(2));

        OrderPage page = queries.list(query(3));

        assertThat(page.orders()).hasSize(2);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void 정확히_limit_만큼이면_커서가_없다() {
        // 경계다. limit+1 을 읽었는데 limit 개만 왔다는 것은 더 없다는 뜻이다.
        when(orders.findByCustomer(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(orders(3));

        assertThat(queries.list(query(3)).nextCursor()).isNull();
    }

    @Test
    void 빈_결과도_페이지다() {
        when(orders.findByCustomer(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        OrderPage page = queries.list(query(3));

        assertThat(page.orders()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void 커서를_그대로_저장소에_넘긴다() {
        UUID cursorId = Ids.newId();
        when(orders.findByCustomer(any(), any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        queries.list(new ListOrdersQuery(CUSTOMER, OrderStatus.CANCELLED, PLACED_AT,
                PLACED_AT.plusSeconds(60), PLACED_AT, cursorId, 5));

        org.mockito.Mockito.verify(orders).findByCustomer(CUSTOMER, OrderStatus.CANCELLED,
                PLACED_AT, PLACED_AT.plusSeconds(60), PLACED_AT, cursorId, 6);
    }

    @Test
    void null_인자는_거부한다() {
        assertThatThrownBy(() -> queries.get(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> queries.list(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderQueryService(null)).isInstanceOf(NullPointerException.class);
    }
}
