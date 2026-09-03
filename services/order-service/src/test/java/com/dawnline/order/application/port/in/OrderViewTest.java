package com.dawnline.order.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.order.domain.DeliveryAddress;
import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderItem;
import com.dawnline.order.domain.OrderStatus;
import com.dawnline.order.domain.Parcel;
import com.dawnline.order.domain.PromisedWindow;
import com.dawnline.order.domain.ServiceTier;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 읽기 모델 변환 (DESIGN.md §5.1). */
@DisplayName("OrderView / OrderSummaryView — 애그리거트 → 읽기 모델")
class OrderViewTest {

    private static final Instant PLACED_AT = Instant.parse("2026-09-03T00:00:00Z");

    private static Order order() {
        return Order.place(Ids.newId(), Ids.newId(), ServiceTier.SAME_DAY,
                DeliveryAddress.of("서울 강남구 테헤란로 1", "06236", GeoPoint.of(37.4979, 127.0276)),
                PromisedWindow.of(PLACED_AT.plus(Duration.ofHours(1)), PLACED_AT.plus(Duration.ofHours(7)),
                        ServiceTier.SAME_DAY),
                new Parcel(1200, 8000, true, false),
                List.of(new OrderItem((short) 1, "SKU-1001", 2), new OrderItem((short) 2, "SKU-2043", 1)),
                PLACED_AT);
    }

    @Test
    void 상세는_모든_필드를_옮긴다() {
        Order order = order();

        OrderView view = OrderView.of(order);

        assertThat(view.orderId()).isEqualTo(order.id());
        assertThat(view.customerId()).isEqualTo(order.customerId());
        assertThat(view.status()).isEqualTo(OrderStatus.PLACED);
        assertThat(view.serviceTier()).isEqualTo(ServiceTier.SAME_DAY);
        assertThat(view.address().line()).isEqualTo("서울 강남구 테헤란로 1");
        assertThat(view.address().lat()).isEqualTo(37.4979);
        assertThat(view.address().geohash7()).isEqualTo(order.address().geohash7());
        assertThat(view.promisedStart()).isEqualTo(order.promisedWindow().start());
        assertThat(view.promisedEnd()).isEqualTo(order.promisedWindow().end());
        assertThat(view.parcel().requiresCold()).isTrue();
        assertThat(view.parcel().hazmat()).isFalse();
        assertThat(view.items()).containsExactly(
                new OrderView.ItemView((short) 1, "SKU-1001", 2),
                new OrderView.ItemView((short) 2, "SKU-2043", 1));
        assertThat(view.placedAt()).isEqualTo(PLACED_AT);
        assertThat(view.updatedAt()).isEqualTo(PLACED_AT);
    }

    @Test
    void 요약에는_주소_문자열이_없다() {
        // 목록 응답은 한 번에 여러 건이 나가고 로그·캐시·프록시를 거치는 경로가 더 넓다 (§9.3).
        OrderSummaryView summary = OrderSummaryView.of(order());

        assertThat(summary.postalCode()).isEqualTo("06236");
        assertThat(summary.geohash7()).hasSize(7);
        assertThat(summary.itemCount()).isEqualTo(2);
        assertThat(summary.toString()).doesNotContain("테헤란로");
    }

    @Test
    void 상태_전이가_읽기_모델에_반영된다() {
        Order order = order();
        order.cancel(PLACED_AT.plusSeconds(30));

        assertThat(OrderView.of(order).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(OrderView.of(order).updatedAt()).isEqualTo(PLACED_AT.plusSeconds(30));
        assertThat(OrderSummaryView.of(order).status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void 품목_목록은_변경할_수_없다() {
        OrderView view = OrderView.of(order());

        assertThatThrownBy(() -> view.items().add(new OrderView.ItemView((short) 3, "SKU-X", 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void null_인자는_거부한다() {
        assertThatThrownBy(() -> OrderView.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> OrderSummaryView.of(null)).isInstanceOf(NullPointerException.class);
    }
}
