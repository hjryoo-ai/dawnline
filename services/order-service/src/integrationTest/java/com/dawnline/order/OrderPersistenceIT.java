package com.dawnline.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.order.application.port.out.OrderRepository;
import com.dawnline.order.domain.DeliveryAddress;
import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderItem;
import com.dawnline.order.domain.OrderStatus;
import com.dawnline.order.domain.Parcel;
import com.dawnline.order.domain.PromisedWindow;
import com.dawnline.order.domain.ServiceTier;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code orders}·{@code order_items} 매핑과 조회 (DESIGN.md §5.1) — 실제 PostgreSQL 18.
 *
 * <p>이 테스트가 없으면 확인되지 않는 것들이다.
 *
 * <ol>
 *   <li>Flyway 가 만든 스키마와 JPA 엔티티가 실제로 맞는가. {@code ddl-auto=validate} 는 컨텍스트가
 *       뜰 때 검증하므로 <strong>이 클래스가 뜬다는 사실 자체가</strong> 그 검증이다.</li>
 *   <li>Hibernate 7 이 record 를 {@code @Embeddable} element collection 으로 다루는가.
 *       {@code OrderItemEntity} 는 무인자 생성자가 없는 record 라 실물로 확인해야 한다.</li>
 *   <li>{@code NUMERIC(9,6)} 왕복 후에도 좌표와 geohash7 이 어긋나지 않는가 — 어긋나면 그 주문은
 *       읽을 때마다 예외가 된다.</li>
 *   <li>커서 페이지네이션이 같은 밀리초의 주문들에서 건너뛰거나 반복하지 않는가.</li>
 * </ol>
 */
@SpringBootTest(classes = OrderApplication.class)
@DisplayName("OrderPersistenceIT — orders 매핑과 커서 조회")
class OrderPersistenceIT extends OrderIntegrationTestBase {

    private static final Instant PLACED_AT = Instant.parse("2026-09-02T10:00:00Z");

    @Autowired
    private OrderRepository orders;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions() {
        return new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void clearOrders() {
        transactions().executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM order_items").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM orders").executeUpdate();
        });
    }

    private static Order order(UUID customerId, Instant placedAt, List<OrderItem> items) {
        return Order.place(Ids.newId(), customerId, ServiceTier.DAWN,
                DeliveryAddress.of("서울 강남구 테헤란로 1", "06236", GeoPoint.of(37.4979, 127.0276)),
                PromisedWindow.of(placedAt.plus(Duration.ofHours(14)),
                        placedAt.plus(Duration.ofHours(21)), ServiceTier.DAWN),
                new Parcel(1200, 8000, false, false), items, placedAt);
    }

    private UUID save(Order order) {
        transactions().executeWithoutResult(status -> orders.save(order));
        return order.id();
    }

    @Test
    void 저장한_주문을_그대로_되살린다() {
        Order original = order(Ids.newId(), PLACED_AT, List.of(
                new OrderItem((short) 1, "SKU-1001", 2),
                new OrderItem((short) 2, "SKU-2043", 1)));
        save(original);

        Order loaded = transactions().execute(status -> orders.findById(original.id()).orElseThrow());

        assertThat(loaded.id()).isEqualTo(original.id());
        assertThat(loaded.customerId()).isEqualTo(original.customerId());
        assertThat(loaded.serviceTier()).isEqualTo(ServiceTier.DAWN);
        assertThat(loaded.status()).isEqualTo(OrderStatus.PLACED);
        assertThat(loaded.address()).isEqualTo(original.address());
        assertThat(loaded.promisedWindow()).isEqualTo(original.promisedWindow());
        assertThat(loaded.parcel()).isEqualTo(original.parcel());
        assertThat(loaded.placedAt()).isEqualTo(PLACED_AT);
        // record 를 embeddable 로 쓴 element collection 이 순서까지 살아 오는가.
        assertThat(loaded.items()).containsExactly(
                new OrderItem((short) 1, "SKU-1001", 2),
                new OrderItem((short) 2, "SKU-2043", 1));
    }

    @Test
    void 좌표는_소수점_6자리로_저장되고_geohash_와_어긋나지_않는다() {
        // 원본 정밀도로 geohash 를 계산해 두면 셀 경계 근처 주소가 왕복에서 다른 셀이 되고,
        // 그 행은 읽을 때마다 예외가 된다.
        Order original = Order.place(Ids.newId(), Ids.newId(), ServiceTier.DAWN,
                DeliveryAddress.of("서울 강남구 테헤란로 1", "06236", GeoPoint.of(37.49791234567, 127.02761987654)),
                PromisedWindow.of(PLACED_AT.plus(Duration.ofHours(14)),
                        PLACED_AT.plus(Duration.ofHours(21)), ServiceTier.DAWN),
                new Parcel(1200, 8000, false, false),
                List.of(new OrderItem((short) 1, "SKU-1", 1)), PLACED_AT);
        save(original);

        Order loaded = transactions().execute(status -> orders.findById(original.id()).orElseThrow());

        assertThat(loaded.address().point().lat()).isEqualTo(37.497912);
        assertThat(loaded.address().point().lng()).isEqualTo(127.027620);
        assertThat(loaded.address().geohash7()).isEqualTo(original.address().geohash7());
        assertThat(loaded.address().isGeohashConsistent()).isTrue();
    }

    @Test
    void TIMESTAMPTZ_는_마이크로초까지만_담는다() {
        // 이것은 "그러면 안 된다" 가 아니라 "실제로 그렇다" 를 고정하는 테스트다. 그래서 시각 출처를
        // 마이크로초로 잘라서 준다(libs/messaging 의 dawnlineClock). 자르지 않으면 접수 응답의
        // placedAt 과 다시 읽은 주문의 placedAt 이 달라지고, 그 차이는 나노초를 주는 플랫폼
        // (Linux)에서만 드러나 개발 기계에서는 보이지 않는다.
        Instant withNanos = Instant.parse("2026-09-03T10:25:07.576754234Z");
        Order original = order(Ids.newId(), withNanos, List.of(new OrderItem((short) 1, "SKU-1", 1)));
        save(original);

        Order loaded = transactions().execute(status -> orders.findById(original.id()).orElseThrow());

        assertThat(loaded.placedAt()).isEqualTo(Instant.parse("2026-09-03T10:25:07.576754Z"));
        assertThat(loaded.placedAt()).isNotEqualTo(withNanos);
    }

    @Test
    void 상태_전이를_반영하면_version_이_올라간다() {
        Order original = order(Ids.newId(), PLACED_AT, List.of(new OrderItem((short) 1, "SKU-1", 1)));
        save(original);

        transactions().executeWithoutResult(status -> {
            Order loaded = orders.findById(original.id()).orElseThrow();
            loaded.markPlanned(PLACED_AT.plusSeconds(60));
            orders.update(loaded);
        });

        Order reloaded = transactions().execute(status -> orders.findById(original.id()).orElseThrow());
        assertThat(reloaded.status()).isEqualTo(OrderStatus.PLANNED);
        assertThat(reloaded.updatedAt()).isEqualTo(PLACED_AT.plusSeconds(60));
        // 도메인이 아니라 Hibernate 가 올린다.
        assertThat(reloaded.version()).isEqualTo(1L);
    }

    @Test
    void 없는_주문을_갱신하면_NotFound_다() {
        Order ghost = order(Ids.newId(), PLACED_AT, List.of(new OrderItem((short) 1, "SKU-1", 1)));

        assertThatThrownBy(() -> transactions().executeWithoutResult(status -> orders.update(ghost)))
                .isInstanceOf(com.dawnline.common.error.NotFoundException.class);
    }

    @Test
    void 없는_주문_조회는_빈_값이다() {
        java.util.Optional<Order> found = transactions().execute(status -> orders.findById(Ids.newId()));
        assertThat(found).isEmpty();
    }

    @Test
    void 커서_페이지네이션이_같은_밀리초에서도_건너뛰거나_반복하지_않는다() {
        UUID customerId = Ids.newId();
        // 전부 같은 접수 시각. placedAt 만으로 커서를 잡으면 여기서 반드시 깨진다.
        for (int i = 0; i < 7; i++) {
            save(order(customerId, PLACED_AT, List.of(new OrderItem((short) 1, "SKU-" + i, 1))));
        }

        List<UUID> collected = new java.util.ArrayList<>();
        Instant cursorAt = null;
        UUID cursorId = null;
        for (int page = 0; page < 5; page++) {
            final Instant at = cursorAt;
            final UUID id = cursorId;
            List<Order> batch = transactions().execute(status ->
                    orders.findByCustomer(customerId, null, null, null, at, id, 3));
            if (batch.isEmpty()) {
                break;
            }
            batch.forEach(o -> collected.add(o.id()));
            Order last = batch.getLast();
            cursorAt = last.placedAt();
            cursorId = last.id();
        }

        assertThat(collected).hasSize(7);
        assertThat(collected).doesNotHaveDuplicates();
        // UUIDv7 은 시간순 정렬이 되므로 같은 시각에서는 id 내림차순이 곧 접수 역순이다.
        assertThat(collected).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void 상태와_기간으로_거를_수_있다() {
        UUID customerId = Ids.newId();
        UUID cancelledId = save(order(customerId, PLACED_AT, List.of(new OrderItem((short) 1, "SKU-1", 1))));
        save(order(customerId, PLACED_AT.plusSeconds(10), List.of(new OrderItem((short) 1, "SKU-2", 1))));
        Instant old = PLACED_AT.minus(Duration.ofDays(3));
        save(order(customerId, old, List.of(new OrderItem((short) 1, "SKU-3", 1))));

        transactions().executeWithoutResult(status -> {
            Order o = orders.findById(cancelledId).orElseThrow();
            o.cancel(PLACED_AT.plusSeconds(5));
            orders.update(o);
        });

        List<Order> cancelled = transactions().execute(status ->
                orders.findByCustomer(customerId, OrderStatus.CANCELLED, null, null, null, null, 10));
        assertThat(cancelled).extracting(Order::id).containsExactly(cancelledId);

        List<Order> recent = transactions().execute(status ->
                orders.findByCustomer(customerId, null, PLACED_AT, null, null, null, 10));
        assertThat(recent).hasSize(2);

        List<Order> before = transactions().execute(status ->
                orders.findByCustomer(customerId, null, null, PLACED_AT, null, null, 10));
        assertThat(before).hasSize(1);
    }

    @Test
    void 다른_고객의_주문은_보이지_않는다() {
        UUID mine = Ids.newId();
        save(order(mine, PLACED_AT, List.of(new OrderItem((short) 1, "SKU-1", 1))));
        save(order(Ids.newId(), PLACED_AT, List.of(new OrderItem((short) 1, "SKU-2", 1))));

        List<Order> mineOnly = transactions().execute(status ->
                orders.findByCustomer(mine, null, null, null, null, null, 10));
        assertThat(mineOnly).hasSize(1);
    }

    @Test
    void 커서_시각만_주고_id_를_빠뜨리면_거부한다() {
        assertThatThrownBy(() -> orders.findByCustomer(Ids.newId(), null, null, null, PLACED_AT, null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursorId");
    }

    @Test
    void 공통_마이그레이션도_같은_위치에서_함께_적용된다() {
        // application.yml 이 flyway locations 를 하나만 두는 이유의 검증이다.
        // libs/messaging 의 V000_x 가 jar 에서 같은 위치로 잡혀야 한다.
        Number tables = transactions().execute(status -> (Number) entityManager.createNativeQuery("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name IN ('orders', 'order_items', 'idempotency_keys',
                                      'outbox_events', 'processed_events')
                """).getSingleResult());

        assertThat(tables.intValue()).isEqualTo(5);
    }
}
