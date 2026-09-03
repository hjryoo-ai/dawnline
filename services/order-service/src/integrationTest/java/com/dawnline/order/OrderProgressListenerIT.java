package com.dawnline.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.messaging.contract.EventContracts;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.order.application.port.out.OrderRepository;
import com.dawnline.order.domain.DeliveryAddress;
import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderItem;
import com.dawnline.order.domain.OrderStatus;
import com.dawnline.order.domain.Parcel;
import com.dawnline.order.domain.PromisedWindow;
import com.dawnline.order.domain.ServiceTier;
import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code order.dispatched}·{@code delivery.status} 리스너 (DESIGN.md §5.1, §4.5, ADR-017) —
 * 실제 Kafka 4.3 + PostgreSQL 18.
 *
 * <p><strong>발행자 없이 완결된다.</strong> 두 이벤트의 생산자는 Phase 3·5 의 dispatch·tracking
 * 이지만, 계약을 소비자가 먼저 정의했으므로(contracts/events/README §1) 여기서는
 * <em>계약 예시 파일</em>을 읽어 식별자만 바꿔 브로커에 직접 발행한다. 그래서 이 테스트가 보는
 * 모양은 상상한 것이 아니라 계약에 적힌 것이다.
 *
 * <p>확인하는 것: 멱등(같은 이벤트 두 번), 순서 뒤바뀜 흡수(완료가 먼저), 철 지난 이벤트 무시,
 * stop 통합(한 이벤트가 여러 주문), 그리고 거부가 DLQ 가 아니라 커밋으로 끝나는지.
 */
@SpringBootTest(classes = OrderApplication.class)
@DisplayName("OrderProgressListenerIT — 배송 진행 이벤트 수신")
class OrderProgressListenerIT extends OrderIntegrationTestBase {

    private static final String ORDER_DISPATCHED_TOPIC = "dawnline.order.dispatched.v1";
    private static final String DELIVERY_STATUS_TOPIC = "dawnline.delivery.status.v1";
    private static final Instant PLACED_AT = Instant.parse("2026-09-03T00:00:00Z");
    private static final EventContracts CONTRACTS = EventContracts.load();
    private static final EventJson JSON = EventJson.standard();

    static {
        // 리스너가 붙기 전에 만들어야 한다. 브로커는 자동 토픽 생성을 꺼 두었다.
        createTopics(ORDER_DISPATCHED_TOPIC, DELIVERY_STATUS_TOPIC);
    }

    @Autowired
    private OrderRepository orders;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 이 테스트의 대상은 소비 경로다. 릴레이는 꺼 둔다 (이유는 {@code PlaceOrderIT} 와 같다). */
    @DynamicPropertySource
    static void noRelay(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    private TransactionTemplate transactions() {
        return new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void clear() {
        transactions().executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM processed_events").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM order_items").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM orders").executeUpdate();
        });
    }

    private UUID seedOrder(OrderStatus status) {
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
            case CANCELLED -> order.cancel(PLACED_AT.plusSeconds(30));
            default -> throw new IllegalArgumentException("시드에 쓰지 않는 상태: " + status);
        }
        transactions().executeWithoutResult(tx -> orders.save(order));
        return order.id();
    }

    /** 계약 예시를 읽어 식별자만 바꾼다. 모양은 계약이 정하고, 값만 이 테스트가 정한다. */
    private ObjectNode exampleOf(String fileName) {
        Path example = CONTRACTS.contractsDirectory().resolve("examples/" + fileName);
        ObjectNode envelope = (ObjectNode) CONTRACTS.readTree(example);
        envelope.put("eventId", Ids.newId().toString());
        return envelope;
    }

    private void publishDispatched(UUID orderId, Instant dispatchedAt, UUID eventId) {
        ObjectNode envelope = exampleOf("order.dispatched.v1.example.json");
        envelope.put("eventId", eventId.toString());
        envelope.put("partitionKey", orderId.toString());
        ObjectNode payload = (ObjectNode) envelope.get("payload");
        payload.put("orderId", orderId.toString());
        payload.put("dispatchedAt", dispatchedAt.toString());
        kafka.send(ORDER_DISPATCHED_TOPIC, orderId.toString(), JSON.write(envelope));
    }

    private void publishDelivery(String status, Instant occurredAt, UUID... orderIds) {
        ObjectNode envelope = exampleOf(status.equals("FAILED")
                ? "delivery.status.v1.failed.example.json"
                : "delivery.status.v1.example.json");
        ObjectNode payload = (ObjectNode) envelope.get("payload");
        payload.put("status", status);
        payload.put("occurredAt", occurredAt.toString());
        payload.remove("orderIds");
        var ids = payload.putArray("orderIds");
        for (UUID id : orderIds) {
            ids.add(id.toString());
        }
        String routeId = payload.get("routeId").asString();
        kafka.send(DELIVERY_STATUS_TOPIC, routeId, JSON.write(envelope));
    }

    private OrderStatus statusOf(UUID orderId) {
        Order order = transactions().execute(tx -> orders.findById(orderId).orElseThrow());
        return order.status();
    }

    private void awaitStatus(UUID orderId, OrderStatus expected) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(statusOf(orderId)).isEqualTo(expected));
    }

    private long processedEventCount() {
        Number count = transactions().execute(tx ->
                (Number) entityManager.createNativeQuery("SELECT count(*) FROM processed_events")
                        .getSingleResult());
        return count == null ? -1 : count.longValue();
    }

    @Test
    void order_dispatched_를_받으면_DISPATCHED_가_된다() {
        UUID orderId = seedOrder(OrderStatus.PLANNED);
        Instant dispatchedAt = PLACED_AT.plus(Duration.ofHours(2));

        publishDispatched(orderId, dispatchedAt, Ids.newId());

        awaitStatus(orderId, OrderStatus.DISPATCHED);
        // 전이 시각은 사건 시각이지 처리 시각이 아니다 (§8.1 정시율이 이 값을 본다).
        Order order = transactions().execute(tx -> orders.findById(orderId).orElseThrow());
        assertThat(order.updatedAt()).isEqualTo(dispatchedAt);
    }

    @Test
    void 같은_이벤트를_두_번_보내도_한_번만_적용된다() {
        // 불변규칙 2. at-least-once 배달에서 이것이 없으면 같은 사실이 두 번 적용된다.
        UUID orderId = seedOrder(OrderStatus.PLANNED);
        UUID eventId = Ids.newId();

        publishDispatched(orderId, PLACED_AT.plus(Duration.ofHours(2)), eventId);
        awaitStatus(orderId, OrderStatus.DISPATCHED);
        publishDispatched(orderId, PLACED_AT.plus(Duration.ofHours(9)), eventId);

        // 두 번째는 processed_events 에서 걸린다. 걸리지 않았다면 updatedAt 이 움직였을 것이다.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Order order = transactions().execute(tx -> orders.findById(orderId).orElseThrow());
            assertThat(order.updatedAt()).isEqualTo(PLACED_AT.plus(Duration.ofHours(2)));
        });
        assertThat(processedEventCount()).isEqualTo(1);
    }

    @Test
    void 배송_완료가_배송_시작보다_먼저_와도_DELIVERED_가_된다() {
        // §4.5 — 두 이벤트는 다른 토픽이라 순서가 보장되지 않는다. 정상 배송이 DLQ 로 가면 안 된다.
        UUID orderId = seedOrder(OrderStatus.PLANNED);

        publishDelivery("COMPLETED", PLACED_AT.plus(Duration.ofHours(6)), orderId);

        awaitStatus(orderId, OrderStatus.DELIVERED);
    }

    @Test
    void 계획보다_먼저_와도_적용된다() {
        // fulfillment.planned 가 늦은 경우. 주문은 아직 PLACED 다.
        UUID orderId = seedOrder(OrderStatus.PLACED);

        publishDispatched(orderId, PLACED_AT.plus(Duration.ofHours(2)), Ids.newId());

        awaitStatus(orderId, OrderStatus.DISPATCHED);
    }

    @Test
    void 뒤늦게_온_배송_시작은_상태를_바꾸지_않는다() {
        UUID orderId = seedOrder(OrderStatus.PLANNED);
        publishDelivery("COMPLETED", PLACED_AT.plus(Duration.ofHours(6)), orderId);
        awaitStatus(orderId, OrderStatus.DELIVERED);
        Instant deliveredAt = transactions()
                .execute(tx -> orders.findById(orderId).orElseThrow()).updatedAt();

        publishDispatched(orderId, PLACED_AT.plus(Duration.ofHours(2)), Ids.newId());

        // 철 지난 이벤트다. 무시하고 커밋 — DLQ 아님 (ADR-017).
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Order order = transactions().execute(tx -> orders.findById(orderId).orElseThrow());
            assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
            assertThat(order.updatedAt()).isEqualTo(deliveredAt);
        });
        assertThat(processedEventCount()).as("무시했어도 소비 기록은 남는다").isEqualTo(2);
    }

    @Test
    void 한_stop_의_주문들이_함께_전이된다() {
        // §6.2 stop 통합 — 같은 격자의 주문들이 한 stop 으로 묶인다.
        UUID first = seedOrder(OrderStatus.DISPATCHED);
        UUID second = seedOrder(OrderStatus.DISPATCHED);

        publishDelivery("FAILED", PLACED_AT.plus(Duration.ofHours(7)), first, second);

        awaitStatus(first, OrderStatus.FAILED);
        awaitStatus(second, OrderStatus.FAILED);
    }

    @Test
    void ARRIVED_는_상태를_바꾸지_않지만_소비_기록은_남는다() {
        // 소비 기록이 없으면 같은 이벤트가 계속 다시 온다.
        UUID orderId = seedOrder(OrderStatus.DISPATCHED);

        publishDelivery("ARRIVED", PLACED_AT.plus(Duration.ofHours(6)), orderId);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(processedEventCount()).isEqualTo(1));
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.DISPATCHED);
    }

    @Test
    void 취소된_주문의_배송_이벤트는_거부되지만_DLQ_로_가지_않는다() {
        // §4.6 3행 — 비즈니스 규칙 위반은 무시하고 커밋한다. processed_events 기록이 그 증거다.
        UUID orderId = seedOrder(OrderStatus.CANCELLED);

        publishDispatched(orderId, PLACED_AT.plus(Duration.ofHours(2)), Ids.newId());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(processedEventCount()).isEqualTo(1));
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CANCELLED);
    }
}
