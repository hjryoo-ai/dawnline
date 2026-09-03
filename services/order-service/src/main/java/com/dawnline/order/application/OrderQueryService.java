package com.dawnline.order.application;

import com.dawnline.common.error.NotFoundException;
import com.dawnline.order.application.port.in.GetOrderUseCase;
import com.dawnline.order.application.port.in.ListOrdersQuery;
import com.dawnline.order.application.port.in.ListOrdersUseCase;
import com.dawnline.order.application.port.in.OrderCursor;
import com.dawnline.order.application.port.in.OrderPage;
import com.dawnline.order.application.port.in.OrderSummaryView;
import com.dawnline.order.application.port.in.OrderView;
import com.dawnline.order.application.port.out.OrderRepository;
import com.dawnline.order.domain.Order;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 조회 (DESIGN.md §5.1 {@code GET /api/v1/orders}, {@code GET /api/v1/orders/{id}}).
 *
 * <h2>다음 커서를 어떻게 아는가</h2>
 * {@code limit + 1} 건을 읽어 한 건이 더 있으면 다음 페이지가 있다고 판정하고, 그 한 건은 버린다.
 * 대안은 마지막 페이지에서도 커서를 주고 클라이언트가 빈 페이지를 한 번 더 받는 것인데, 그러면
 * 모든 목록 조회가 요청을 한 번 더 하게 된다. 한 행을 더 읽는 편이 싸다.
 *
 * <h2>{@code readOnly}</h2>
 * 읽기 전용 트랜잭션으로 연다. Hibernate 가 더티 체킹을 건너뛰고, 무엇보다 <strong>이 경로에서
 * 쓰기가 일어나면 DB 가 거부한다</strong> — 조회가 상태를 바꾸지 않는다는 것을 주석이 아니라
 * 런타임이 보장한다.
 */
public class OrderQueryService implements GetOrderUseCase, ListOrdersUseCase {

    private final OrderRepository orders;

    /**
     * @param orders 주문 저장소
     */
    public OrderQueryService(OrderRepository orders) {
        this.orders = Objects.requireNonNull(orders, "orders");
    }

    @Override
    @Transactional(readOnly = true)
    public OrderView get(UUID orderId) {
        Objects.requireNonNull(orderId, "orderId");
        return orders.findById(orderId)
                .map(OrderView::of)
                .orElseThrow(() -> NotFoundException.of("Order", orderId));
    }

    @Override
    @Transactional(readOnly = true)
    public OrderPage list(ListOrdersQuery query) {
        Objects.requireNonNull(query, "query");

        List<Order> found = orders.findByCustomer(query.customerId(), query.status(),
                query.from(), query.to(), query.cursorPlacedAt(), query.cursorId(), query.limit() + 1);

        boolean hasMore = found.size() > query.limit();
        List<Order> page = hasMore ? found.subList(0, query.limit()) : found;
        OrderCursor nextCursor = hasMore
                ? new OrderCursor(page.getLast().placedAt(), page.getLast().id())
                : null;

        return new OrderPage(page.stream().map(OrderSummaryView::of).toList(), nextCursor);
    }
}
