package com.dawnline.order.adapter.out.persistence;

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

/**
 * 도메인 ↔ 엔티티 변환 (ADR-007, DESIGN.md §5.1).
 *
 * <p>{@code OrderPersistenceIT} 가 실물 DB 왕복을 보는 것과 별개로, 변환 자체는 DB 없이 검증할 수
 * 있고 그래야 한다. 컬럼이 늘었는데 {@code from} 이나 {@code toDomain} 한쪽만 고치는 실수는
 * 통합 테스트보다 여기서 훨씬 빨리 잡힌다.
 */
@DisplayName("OrderEntity — 도메인 ↔ 행 변환")
class OrderEntityTest {

    private static final Instant PLACED_AT = Instant.parse("2026-09-02T10:00:00Z");
    private static final GeoPoint GANGNAM = GeoPoint.of(37.4979, 127.0276);

    private static Order order(OrderStatus status) {
        Order placed = Order.place(Ids.newId(), Ids.newId(), ServiceTier.SAME_DAY,
                DeliveryAddress.of("서울 강남구 테헤란로 1", "06236", GANGNAM),
                PromisedWindow.of(PLACED_AT.plus(Duration.ofHours(1)),
                        PLACED_AT.plus(Duration.ofHours(6)), ServiceTier.SAME_DAY),
                new Parcel(1200, 8000, true, false),
                List.of(new OrderItem((short) 1, "SKU-1001", 2),
                        new OrderItem((short) 2, "SKU-2043", 1)),
                PLACED_AT);
        if (status == OrderStatus.PLANNED) {
            placed.markPlanned(PLACED_AT.plusSeconds(60));
        }
        return placed;
    }

    @Test
    void 모든_필드가_왕복에서_보존된다() {
        Order original = order(OrderStatus.PLACED);

        Order restored = OrderEntity.from(original).toDomain();

        assertThat(restored.id()).isEqualTo(original.id());
        assertThat(restored.customerId()).isEqualTo(original.customerId());
        assertThat(restored.serviceTier()).isEqualTo(original.serviceTier());
        assertThat(restored.status()).isEqualTo(original.status());
        assertThat(restored.address()).isEqualTo(original.address());
        assertThat(restored.promisedWindow()).isEqualTo(original.promisedWindow());
        assertThat(restored.parcel()).isEqualTo(original.parcel());
        assertThat(restored.items()).isEqualTo(original.items());
        assertThat(restored.placedAt()).isEqualTo(original.placedAt());
        assertThat(restored.updatedAt()).isEqualTo(original.updatedAt());
    }

    @Test
    void 냉장_위험물_플래그가_뒤집히지_않는다() {
        // boolean 두 개를 나란히 옮기는 코드는 순서를 바꿔 써도 컴파일된다.
        // 냉장 사고는 이런 식으로 난다.
        Order coldOnly = Order.place(Ids.newId(), Ids.newId(), ServiceTier.DAWN,
                DeliveryAddress.of("서울 강남구 테헤란로 1", "06236", GANGNAM),
                PromisedWindow.of(PLACED_AT.plus(Duration.ofHours(14)),
                        PLACED_AT.plus(Duration.ofHours(21)), ServiceTier.DAWN),
                new Parcel(500, 1000, true, false),
                List.of(new OrderItem((short) 1, "SKU-1", 1)), PLACED_AT);

        Parcel restored = OrderEntity.from(coldOnly).toDomain().parcel();

        assertThat(restored.requiresCold()).isTrue();
        assertThat(restored.hazmat()).isFalse();
    }

    @Test
    void 품목은_순번_순서로_되살아난다() {
        // element collection 은 순서를 보장하지 않는다. toDomain 이 정렬해야 한다.
        Order original = order(OrderStatus.PLACED);

        List<OrderItem> restored = OrderEntity.from(original).toDomain().items();

        assertThat(restored).extracting(OrderItem::lineNo).containsExactly((short) 1, (short) 2);
    }

    @Test
    void 새_엔티티의_version_은_0_이다() {
        OrderEntity entity = OrderEntity.from(order(OrderStatus.PLACED));

        assertThat(entity.version()).isZero();
        assertThat(entity.toDomain().version()).isZero();
    }

    @Test
    void applyStateOf_는_상태와_갱신시각만_바꾼다() {
        Order original = order(OrderStatus.PLACED);
        OrderEntity entity = OrderEntity.from(original);

        Order transitioned = OrderEntity.from(original).toDomain();
        transitioned.markPlanned(PLACED_AT.plusSeconds(90));
        entity.applyStateOf(transitioned);

        Order result = entity.toDomain();
        assertThat(result.status()).isEqualTo(OrderStatus.PLANNED);
        assertThat(result.updatedAt()).isEqualTo(PLACED_AT.plusSeconds(90));
        // 불변 필드는 그대로다.
        assertThat(result.placedAt()).isEqualTo(PLACED_AT);
        assertThat(result.address()).isEqualTo(original.address());
        assertThat(result.items()).isEqualTo(original.items());
    }

    @Test
    void applyStateOf_는_다른_주문의_상태를_받지_않는다() {
        OrderEntity entity = OrderEntity.from(order(OrderStatus.PLACED));

        assertThatThrownBy(() -> entity.applyStateOf(order(OrderStatus.PLANNED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("다른 주문");
    }

    @Test
    void id_와_status_접근자가_행의_값을_그대로_준다() {
        Order original = order(OrderStatus.PLANNED);

        OrderEntity entity = OrderEntity.from(original);

        assertThat(entity.id()).isEqualTo(original.id());
        assertThat(entity.status()).isEqualTo(OrderStatus.PLANNED);
    }
}
