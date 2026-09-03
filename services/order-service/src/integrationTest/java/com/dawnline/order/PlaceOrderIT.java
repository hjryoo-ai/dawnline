package com.dawnline.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.Ids;
import com.dawnline.common.error.DomainException;
import com.dawnline.order.application.port.in.PlaceOrderCommand;
import com.dawnline.order.application.port.in.PlaceOrderResult;
import com.dawnline.order.application.port.in.PlaceOrderUseCase;
import com.dawnline.order.application.port.out.IdempotencyCache;
import com.dawnline.order.application.port.out.OrderRepository;
import com.dawnline.order.domain.Order;
import com.dawnline.order.domain.OrderItem;
import com.dawnline.order.domain.OrderStatus;
import com.dawnline.order.domain.Parcel;
import com.dawnline.order.domain.ServiceTier;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문 접수 유스케이스 (DESIGN.md §5.1, ADR-018) — 실제 PostgreSQL 18, <strong>Redis 없음</strong>.
 *
 * <p>Redis 를 일부러 죽은 주소로 가리킨다. 그래서 이 클래스가 통과한다는 것은
 * <strong>Redis 가 통째로 없어도 멱등이 성립한다</strong>는 증명이다 — CLAUDE.md 불변규칙 7 과
 * §8.4 가 요구하는 것이고, Phase 1 DoD 2 항의 근거다. (HTTP 계층까지 포함한 증명은 Phase 1-8 에서
 * 같은 조건으로 다시 한다.)
 *
 * <p>Redis 를 켜 둔 개발 기계에서도 결과가 같도록 호스트를 고정한다. 로컬에 Redis 가 떠 있는지에
 * 따라 검사 대상이 달라지는 테스트는 아무것도 증명하지 못한다.
 */
@SpringBootTest(classes = OrderApplication.class)
@DisplayName("PlaceOrderIT — Redis 없이도 성립하는 멱등 접수")
class PlaceOrderIT extends OrderIntegrationTestBase {

    @Autowired
    private PlaceOrderUseCase placeOrder;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private IdempotencyCache cache;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Redis 는 아무도 듣지 않는 포트로, outbox 릴레이는 꺼 둔다.
     *
     * <p>Redis: Lettuce 가 연결 거부를 즉시 받고 {@code UNAVAILABLE} 로 바뀐다.
     *
     * <p>릴레이: 이 테스트가 보는 것은 <em>쓰기 경로</em>다 — 주문과 같은 트랜잭션에서 outbox 행이
     * 생기는가. 브로커로 실제로 보내는 것은 {@code libs/messaging} 의 {@code OutboxRelayIT} 가 본다.
     * 켜 두면 두 가지가 나빠진다. (1) 테스트 브로커는 자동 토픽 생성을 꺼 두었으므로 릴레이가
     * {@code UNKNOWN_TOPIC_OR_PARTITION} 으로 무한 재시도하며 컨텍스트 종료를 30초씩 붙잡는다.
     * (2) {@code published_at IS NULL} 어설션이 릴레이 폴링(100ms)과 경합하는 시한부 검사가 된다.
     * {@code OutboxAppender} 는 이 플래그와 무관하게 그대로 동작한다.
     */
    @DynamicPropertySource
    static void deadRedisAndNoRelay(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> "1");
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    private TransactionTemplate transactions() {
        return new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void clear() {
        transactions().executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM outbox_events").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM idempotency_keys").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM order_items").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM orders").executeUpdate();
        });
    }

    private static PlaceOrderCommand command(String key, UUID customerId) {
        return new PlaceOrderCommand(key, customerId, ServiceTier.DAWN,
                "서울 강남구 테헤란로 1", "06236",
                new Parcel(1200, 8000, false, false),
                List.of(new OrderItem((short) 1, "SKU-1001", 2), new OrderItem((short) 2, "SKU-2043", 1)));
    }

    private long count(String table) {
        Number count = transactions().execute(status ->
                (Number) entityManager.createNativeQuery("SELECT count(*) FROM " + table).getSingleResult());
        return count == null ? -1 : count.longValue();
    }

    @Test
    void Redis_가_없으면_잠금이_UNAVAILABLE_이다() {
        // 이 테스트가 통과해야 아래 테스트들이 "Redis 없이" 를 증명한다.
        assertThat(cache.tryLock("아무-키")).isEqualTo(IdempotencyCache.Lock.UNAVAILABLE);
    }

    @Test
    void 접수는_주문_품목_이벤트_멱등기록을_한_번에_남긴다() {
        PlaceOrderResult result = placeOrder.place(command("idem-a", Ids.newId()));

        assertThat(result.replayed()).isFalse();
        assertThat(result.order().status()).isEqualTo(OrderStatus.PLACED);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("order_items")).isEqualTo(2);
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(count("idempotency_keys")).isEqualTo(1);
    }

    @Test
    void outbox_행이_order_placed_계약대로_들어간다() {
        PlaceOrderResult result = placeOrder.place(command("idem-b", Ids.newId()));

        Object[] row = transactions().execute(status -> (Object[]) entityManager.createNativeQuery("""
                SELECT aggregate_type, aggregate_id, event_type, topic, partition_key,
                       CAST(payload AS text), published_at
                  FROM outbox_events
                """).getSingleResult());

        assertThat(row[0]).isEqualTo("order");
        assertThat(row[1]).hasToString(result.order().orderId().toString());
        assertThat(row[2]).isEqualTo("order.placed");
        assertThat(row[3]).isEqualTo("dawnline.order.placed.v1");
        // §4.5 — 같은 주문의 이벤트는 같은 파티션으로.
        assertThat(row[4]).isEqualTo(result.order().orderId().toString());
        assertThat((String) row[5]).contains("\"geohash7\"").contains("SKU-2043");
        // §5.2 웨이브 키가 쓰는 값이다. 빠지면 fulfillment 가 §2.2 표를 다시 들고 계산하게 된다.
        assertThat((String) row[5]).contains("\"cutoffAt\"");
        assertThat(row[6]).as("릴레이가 아직 보내지 않았어야 한다").isNull();
    }

    @Test
    void 같은_키로_다시_보내면_저장된_응답을_재생하고_주문은_하나뿐이다() {
        UUID customerId = Ids.newId();
        PlaceOrderResult first = placeOrder.place(command("idem-c", customerId));

        PlaceOrderResult second = placeOrder.place(command("idem-c", customerId));

        assertThat(second.replayed()).isTrue();
        assertThat(second.order()).isEqualTo(first.order());
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("outbox_events")).as("이벤트도 한 번만 나가야 한다").isEqualTo(1);
    }

    @Test
    void 같은_키에_다른_본문이면_422_이고_아무것도_바뀌지_않는다() {
        UUID customerId = Ids.newId();
        placeOrder.place(command("idem-d", customerId));

        PlaceOrderCommand different = new PlaceOrderCommand("idem-d", customerId, ServiceTier.SAME_DAY,
                "서울 강남구 테헤란로 1", "06236",
                new Parcel(1200, 8000, false, false),
                List.of(new OrderItem((short) 1, "SKU-1001", 2)));

        assertThatThrownBy(() -> placeOrder.place(different))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).status()).isEqualTo(422));
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    void 좌표를_찾을_수_없는_주소는_아무것도_남기지_않는다() {
        PlaceOrderCommand busan = new PlaceOrderCommand("idem-e", Ids.newId(), ServiceTier.DAWN,
                "부산 해운대구 우동 1", "48058",
                new Parcel(1200, 8000, false, false),
                List.of(new OrderItem((short) 1, "SKU-1", 1)));

        assertThatThrownBy(() -> placeOrder.place(busan))
                .isInstanceOf(com.dawnline.common.error.ValidationException.class);

        assertThat(count("orders")).isZero();
        assertThat(count("idempotency_keys")).isZero();
    }

    @Test
    void 저장된_주문을_다시_읽으면_약속창과_좌표가_그대로다() {
        PlaceOrderResult result = placeOrder.place(command("idem-f", Ids.newId()));

        Order loaded = transactions()
                .execute(status -> orders.findById(result.order().orderId()).orElseThrow());

        assertThat(loaded.promisedWindow().start()).isEqualTo(result.order().promisedStart());
        assertThat(loaded.promisedWindow().end()).isEqualTo(result.order().promisedEnd());
        assertThat(loaded.placedAt()).isEqualTo(result.order().placedAt());
        // NUMERIC(9,6) 왕복 후에도 geohash 와 좌표가 어긋나지 않아야 한다.
        assertThat(loaded.address().isGeohashConsistent()).isTrue();
        assertThat(loaded.items()).hasSize(2);
    }
}
