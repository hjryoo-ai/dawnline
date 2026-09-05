package com.dawnline.order.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.order.application.port.in.ApplyFulfillmentPlanUseCase.PlanApplication;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

/**
 * {@code fulfillment.planned} 반영 (§5.2 6단계, ADR-017 경고, ADR-020 결정 3).
 *
 * <p>이 클래스의 요점은 <strong>상태 전이와 데이터 부착이 다른 일</strong>이라는 것이다.
 * ADR-017 이 Phase 2 를 위해 미리 적어 둔 함정이고, 그것을 테스트로 못 박는다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("ApplyFulfillmentPlanService — 전이와 데이터 부착은 다른 일이다")
class ApplyFulfillmentPlanServiceTest {

    private static final Instant PLACED_AT = Instant.parse("2026-09-05T01:00:00Z");
    private static final Instant AT = PLACED_AT.plusSeconds(120);
    private static final PromisedWindow ORIGINAL = PromisedWindow.of(
            PLACED_AT.plus(Duration.ofHours(14)), PLACED_AT.plus(Duration.ofHours(21)), ServiceTier.DAWN);
    private static final PromisedWindow REVISED = PromisedWindow.of(
            PLACED_AT.plus(Duration.ofHours(38)), PLACED_AT.plus(Duration.ofHours(45)), ServiceTier.DAWN);

    private final InMemoryOrders orders = new InMemoryOrders();
    private final ApplyFulfillmentPlanService service = new ApplyFulfillmentPlanService(orders);

    private Order saved(OrderStatus status) {
        Order order = Order.rehydrate(Ids.newId(), Ids.newId(), ServiceTier.DAWN,
                DeliveryAddress.of("서울 강남구 테헤란로 1", "06236", GeoPoint.of(37.4979, 127.0276)),
                ORIGINAL, new Parcel(1200, 8000, false, false),
                List.of(new OrderItem((short) 1, "SKU-1001", 2)),
                status, PLACED_AT, PLACED_AT, 0, null);
        orders.save(order);
        return order;
    }

    // --- 계획됨 ---------------------------------------------------------------

    @Test
    void 계획되면_PLANNED_로_전이한다() {
        Order order = saved(OrderStatus.PLACED);

        assertThat(service.planned(order.id(), null, false, AT)).isEqualTo(PlanApplication.APPLIED);
        assertThat(orders.get(order.id()).status()).isEqualTo(OrderStatus.PLANNED);
    }

    @Test
    void 개정되면_약속창이_갱신된다() {
        // 지금까지 promisedWindow 는 불변이었고, 그 불변성을 푸는 것은 이 한 경로뿐이다.
        Order order = saved(OrderStatus.PLACED);

        assertThat(service.planned(order.id(), REVISED, true, AT)).isEqualTo(PlanApplication.APPLIED);
        assertThat(orders.get(order.id()).promisedWindow()).isEqualTo(REVISED);
    }

    @Test
    void 개정이_아니면_약속창을_건드리지_않는다() {
        Order order = saved(OrderStatus.PLACED);

        service.planned(order.id(), REVISED, false, AT);

        assertThat(orders.get(order.id()).promisedWindow())
                .as("개정 플래그가 없으면 창을 바꾸지 않는다 — 같은 값이어도 경로가 다르다")
                .isEqualTo(ORIGINAL);
    }

    // --- ADR-017 의 경고 --------------------------------------------------------

    @Test
    void 늦게_와도_개정된_약속은_반영한다() {
        // 주문이 이미 DISPATCHED 다. 전이만 보면 stale 이지만, 개정된 창까지 버리면 고객이 보는
        // 값이 낡은 채로 남는다 — ADR-017 이 Phase 2 를 위해 적어 둔 바로 그 함정이다.
        Order order = saved(OrderStatus.DISPATCHED);

        PlanApplication result = service.planned(order.id(), REVISED, true, AT);

        assertThat(result).isEqualTo(PlanApplication.STALE_BUT_DATA_APPLIED);
        assertThat(orders.get(order.id()).status()).as("전이는 하지 않는다").isEqualTo(OrderStatus.DISPATCHED);
        assertThat(orders.get(order.id()).promisedWindow()).as("데이터는 반영한다").isEqualTo(REVISED);
    }

    @Test
    void 늦게_왔고_반영할_데이터도_없으면_그냥_stale_이다() {
        // STALE 과 STALE_BUT_DATA_APPLIED 를 나누는 이유는 세는 값이 다르기 때문이다.
        Order order = saved(OrderStatus.DELIVERED);

        assertThat(service.planned(order.id(), null, false, AT)).isEqualTo(PlanApplication.STALE);
        assertThat(orders.get(order.id()).promisedWindow()).isEqualTo(ORIGINAL);
    }

    @Test
    void 취소된_주문에는_개정도_전이도_하지_않는다() {
        // CANCELLED 는 축 밖이다 (ADR-017). 취소된 주문의 약속을 갱신할 이유가 없다.
        Order order = saved(OrderStatus.CANCELLED);

        assertThat(service.planned(order.id(), REVISED, true, AT)).isEqualTo(PlanApplication.REJECTED);
        assertThat(orders.get(order.id()).promisedWindow()).isEqualTo(ORIGINAL);
    }

    // --- 배차 불가 -------------------------------------------------------------

    @Test
    void 배차_불가는_FAILED_와_사유를_남긴다() {
        // 상태만으로는 "배달을 시도했다 실패한 것" 과 구별되지 않는다. 고객에게 "왜" 를 답해야 한다.
        Order order = saved(OrderStatus.PLACED);

        assertThat(service.unserviceable(order.id(), "NO_ELIGIBLE_FC", AT))
                .isEqualTo(PlanApplication.APPLIED);
        Order applied = orders.get(order.id());
        assertThat(applied.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(applied.failureReason()).contains("NO_ELIGIBLE_FC");
    }

    @Test
    void 계획된_주문도_배차_불가로_종결될_수_있다() {
        // 개정 경로에서 다음 웨이브를 찾다 STALE_PLACED 가 되는 경우가 그렇다.
        Order order = saved(OrderStatus.PLANNED);

        assertThat(service.unserviceable(order.id(), "STALE_PLACED", AT))
                .isEqualTo(PlanApplication.APPLIED);
        assertThat(orders.get(order.id()).failureReason()).contains("STALE_PLACED");
    }

    @Test
    void 취소된_주문을_배차_불가로_덮지_않는다() {
        Order order = saved(OrderStatus.CANCELLED);

        assertThat(service.unserviceable(order.id(), "NO_ZONE_MATCH", AT))
                .isEqualTo(PlanApplication.REJECTED);
        assertThat(orders.get(order.id()).status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void 모르는_주문은_조용히_지나간다() {
        assertThat(service.planned(Ids.newId(), null, false, AT))
                .isEqualTo(PlanApplication.ORDER_NOT_FOUND);
        assertThat(service.unserviceable(Ids.newId(), "NO_ZONE_MATCH", AT))
                .isEqualTo(PlanApplication.ORDER_NOT_FOUND);
    }

    /** 인메모리 주문 저장소. 되살린 인스턴스가 별개라는 사실까지 흉내 낸다. */
    private static final class InMemoryOrders implements OrderRepository {

        private final Map<UUID, Order> byId = new LinkedHashMap<>();

        Order get(UUID id) {
            return byId.get(id);
        }

        @Override
        public void save(Order order) {
            byId.put(order.id(), order);
        }

        @Override
        public void update(Order order) {
            byId.put(order.id(), order);
        }

        @Override
        public Optional<Order> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<Order> findByCustomer(UUID customerId, @Nullable OrderStatus status,
                @Nullable Instant from, @Nullable Instant to,
                @Nullable Instant cursorPlacedAt, @Nullable UUID cursorId, int limit) {
            return new ArrayList<>(byId.values());
        }
    }
}
