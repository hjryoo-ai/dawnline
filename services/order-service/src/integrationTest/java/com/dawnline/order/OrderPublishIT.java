package com.dawnline.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.common.Ids;
import com.dawnline.messaging.contract.EventContracts;
import com.dawnline.order.application.port.in.PlaceOrderCommand;
import com.dawnline.order.application.port.in.PlaceOrderResult;
import com.dawnline.order.application.port.in.PlaceOrderUseCase;
import com.dawnline.order.domain.OrderItem;
import com.dawnline.order.domain.Parcel;
import com.dawnline.order.domain.ServiceTier;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 발행이 브로커까지 가서 계약을 지키는가 (DESIGN.md §4.2·§4.4, 불변규칙 1·8).
 *
 * <p><strong>대조표를 만들다 드러난 빈 칸이다.</strong> 릴레이 자체는 {@code libs/messaging} 의
 * IT 가 보고, 페이로드 계약은 {@code OrderPlacedContractTest} 가 본다. 그런데 <em>order-service 의
 * 발행이 실제로 브로커에 도착해 봉투까지 계약을 지키는가</em> 는 그 둘 사이에 끼어 아무도 보지
 * 않았다 — 다른 IT 들은 릴레이를 꺼 두었기 때문이다.
 *
 * <p>여기서만 릴레이를 켠다. 그래서 이 클래스가 확인하는 것은 세 가지다.
 *
 * <p><strong>2026-09-05 하루 동안 이 클래스는 Redis 컨테이너를 띄웠다.</strong> 릴레이 리더 락이
 * Redis 였을 때는 리더십을 판정할 수 없으면 아무것도 발행되지 않았고, 그래서 "Redis 는 있으나
 * 없으나 발행 경로는 같다" 는 원래 주석이 거짓이 됐다([ADR-027]). 락을 advisory lock 으로
 * 옮기면서 그 주석이 다시 참이 됐다 — 발행 경로는 DB 와 Kafka 뿐이다.
 *
 * <ol>
 *   <li>outbox 행이 실제로 발행되고 {@code published_at} 이 찍히는가</li>
 *   <li>브로커에 도착한 레코드가 <strong>봉투까지</strong> 계약을 지키는가
 *       ({@code EventContracts.validateRecord} — envelope + payload 양쪽)</li>
 *   <li>파티션 키가 §4.5 대로 주문 id 인가</li>
 * </ol>
 */
@SpringBootTest(classes = OrderApplication.class)
@DisplayName("OrderPublishIT — outbox → 릴레이 → 브로커 → 계약")
class OrderPublishIT extends OrderIntegrationTestBase {

    private static final String TOPIC = "dawnline.order.placed.v1";
    private static final EventContracts CONTRACTS = EventContracts.load();

    private static KafkaConsumer<String, String> consumer;

    static {
        // 릴레이가 붙기 전에 만들어야 한다. 브로커는 자동 토픽 생성을 꺼 두었다.
        createTopics(TOPIC);
    }

    @Autowired
    private PlaceOrderUseCase placeOrder;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeAll
    static void subscribe() {
        // 애플리케이션의 소비자 그룹(order-service)과 겹치지 않게 별도 그룹을 쓴다.
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "order-publish-it-" + Ids.newId(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        consumer.subscribe(List.of(TOPIC));
    }

    @AfterAll
    static void unsubscribe() {
        consumer.close();
    }

    private TransactionTemplate transactions() {
        return new TransactionTemplate(transactionManager);
    }

    private static PlaceOrderCommand command(UUID customerId, String key) {
        return new PlaceOrderCommand(key, customerId, ServiceTier.DAWN,
                "서울 강남구 테헤란로 1", "06236",
                new Parcel(1200, 8000, false, false),
                List.of(new OrderItem((short) 1, "SKU-1001", 2)));
    }

    /** 이 주문 id 의 레코드가 올 때까지 폴링한다. 다른 테스트가 남긴 레코드와 섞이지 않게. */
    private ConsumerRecord<String, String> awaitRecordFor(UUID orderId) {
        List<ConsumerRecord<String, String>> seen = new ArrayList<>();
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(200));
            polled.forEach(seen::add);
            assertThat(seen).anyMatch(record -> orderId.toString().equals(record.key()));
        });
        return seen.stream()
                .filter(record -> orderId.toString().equals(record.key()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void 접수한_주문이_브로커에_도착하고_봉투까지_계약을_지킨다() {
        UUID customerId = Ids.newId();
        PlaceOrderResult result = placeOrder.place(command(customerId, "publish-" + customerId));
        UUID orderId = result.order().orderId();

        ConsumerRecord<String, String> record = awaitRecordFor(orderId);

        // 봉투 + 페이로드 양쪽을 계약으로 검증한다. 단위 계약 테스트는 페이로드만 본다.
        CONTRACTS.validateRecord(record.value());

        JsonNode envelope = CONTRACTS.json().readTree(record.value());
        assertThat(envelope.get("eventType").asString()).isEqualTo("order.placed");
        assertThat(envelope.get("producer").asString()).isEqualTo("order-service");
        assertThat(envelope.get("schemaVersion").intValue()).isEqualTo(1);
        assertThat(envelope.get("payload").get("orderId").asString()).isEqualTo(orderId.toString());
        // §4.5 — 같은 주문의 이벤트는 같은 파티션으로 가야 순서가 보장된다.
        assertThat(record.key()).isEqualTo(orderId.toString());
    }

    @Test
    void 발행된_outbox_행에_published_at_이_찍힌다() {
        // 릴레이가 실제로 보냈다는 것을 DB 쪽에서도 확인한다. 이 값이 안 찍히면 같은 이벤트가
        // 계속 다시 나간다(§4.4).
        UUID customerId = Ids.newId();
        UUID orderId = placeOrder.place(command(customerId, "published-at-" + customerId))
                .order().orderId();

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Number published = transactions().execute(status -> (Number) entityManager.createNativeQuery("""
                    SELECT count(*) FROM outbox_events
                     WHERE aggregate_id = CAST(:orderId AS uuid) AND published_at IS NOT NULL
                    """).setParameter("orderId", orderId.toString()).getSingleResult());
            assertThat(published).isNotNull();
            assertThat(published.longValue()).isEqualTo(1L);
        });
    }
}
