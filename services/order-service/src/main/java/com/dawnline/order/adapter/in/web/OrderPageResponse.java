package com.dawnline.order.adapter.in.web;

import com.dawnline.order.application.port.in.OrderPage;
import com.dawnline.order.application.port.in.OrderSummaryView;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /api/v1/orders} 응답 (DESIGN.md §5.1 커서 페이지네이션).
 *
 * <p>커서를 불투명한 문자열로 내보내는 것이 이 레코드의 존재 이유다. {@code (placedAt, id)} 두
 * 값을 그대로 노출하면 클라이언트가 그것을 조립하기 시작하고, 그 순간 정렬 키가 API 계약이 된다.
 *
 * @param orders     이 페이지의 주문들. 접수 시각 내림차순
 * @param nextCursor 다음 페이지 커서. {@code null} 이면 마지막 페이지다
 */
public record OrderPageResponse(List<OrderSummaryView> orders, @Nullable String nextCursor) {

    /**
     * @param page 유스케이스 결과
     */
    public static OrderPageResponse of(OrderPage page) {
        return new OrderPageResponse(page.orders(),
                page.nextCursor() == null ? null : Cursors.encode(page.nextCursor()));
    }
}
