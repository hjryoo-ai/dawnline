package com.dawnline.order.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Ids;
import com.dawnline.order.application.port.in.OrderCursor;
import com.dawnline.order.application.port.in.OrderPage;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 목록 응답의 커서 노출 (DESIGN.md §5.1). */
@DisplayName("OrderPageResponse — 커서를 불투명 문자열로")
class OrderPageResponseTest {

    @Test
    void 마지막_페이지면_커서가_null_이다() {
        OrderPageResponse response = OrderPageResponse.of(new OrderPage(List.of(), null));

        assertThat(response.nextCursor()).isNull();
        assertThat(response.orders()).isEmpty();
    }

    @Test
    void 다음이_있으면_커서를_인코딩해_준다() {
        OrderCursor cursor = new OrderCursor(Instant.parse("2026-09-03T00:00:00Z"), Ids.newId());

        OrderPageResponse response = OrderPageResponse.of(new OrderPage(List.of(), cursor));

        assertThat(response.nextCursor()).isNotNull();
        // 클라이언트가 해석할 수 없어야 한다 — 그래야 정렬 키가 API 계약이 되지 않는다.
        assertThat(response.nextCursor()).doesNotContain("2026").doesNotContain("|");
        assertThat(Cursors.decode(response.nextCursor())).isEqualTo(cursor);
    }
}
