package com.dawnline.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.common.GeoPoint;
import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.fulfillment.application.port.in.PlacedOrderSnapshot;
import com.dawnline.fulfillment.application.port.in.PlanOrderUseCase;
import com.dawnline.fulfillment.application.port.out.ReferenceData;
import com.dawnline.messaging.contract.EventContracts;
import java.time.Duration;
import java.time.Instant;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

/**
 * {@code fulfillment.planned} 가 브로커까지 가서 계약을 지키는가 (§4.2·§4.4, 불변규칙 1·8).
 *
 * <p>Phase 2-5 마감 대조표의 열 하나다. 릴레이 자체는 {@code libs/messaging} 의 IT 가 보고,
 * 페이로드 모양은 단위 테스트가 본다. 그런데 <em>이 서비스의 발행이 실제로 브로커에 도착해
 * 봉투까지 계약을 지키는가</em> 는 그 둘 사이에 끼어 아무도 보지 않는다 — 다른 IT 들은 릴레이를
 * 꺼 두기 때문이다. Phase 1 이 {@code OrderPublishIT} 로 같은 빈 칸을 메웠고, 그것을 규칙으로
 * 삼았다.
 *
 * <p>여기서만 릴레이를 켠다.
 */
@SpringBootTest(classes = FulfillmentApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("FulfillmentPublishIT — outbox → 릴레이 → 브로커 → 계약")
class FulfillmentPublishIT extends FulfillmentIntegrationTestBase {

    private static final String TOPIC = "dawnline.fulfillment.planned.v1";
    private static final EventContracts CONTRACTS = EventContracts.load();

    private static KafkaConsumer<String, String> consumer;

    static {
        // 릴레이가 붙기 전에 만들어야 한다. 브로커는 자동 토픽 생성을 꺼 두었다.
        createTopics(TOPIC, "dawnline.order.placed.v1", "dawnline.order.cancelled.v1");
    }

    @Autowired
    private PlanOrderUseCase planOrder;

    @Autowired
    private com.dawnline.messaging.outbox.OutboxRelay relay;

    /**
     * <strong>전제: 릴레이가 실제로 돌고 있다.</strong>
     *
     * <p>이 클래스가 보는 것은 "outbox 행이 브로커까지 간다" 이므로, 릴레이가 꺼져 있으면 아무것도
     * 증명하지 못한 채 60초를 기다리다 실패한다 — 실제로 그렇게 실패했다(기반 클래스의
     * {@code @DynamicPropertySource} 가 릴레이를 끄고 있었고, 두 등록의 순서는 보장되지 않는다).
     *
     * <p>CLAUDE.md 의 규칙대로 전제를 첫 어설션으로 둔다. 폴백 테스트에서 배운 것과 같은 형태다 —
     * 전제가 조용히 무너지면 테스트는 아무것도 검사하지 않는다.
     */
    @BeforeEach
    void 전제_릴레이가_돈다() {
        assertThat(relay).as("릴레이 빈이 없으면 발행 경로가 통째로 없다").isNotNull();
    }

    @Autowired
    private ReferenceData referenceData;

    @BeforeAll
    static void subscribe() {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "fulfillment-publish-it-" + Ids.newId(),
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

    @Test
    void 계획된_주문이_브로커에_도착하고_봉투까지_계약을_지킨다() {
        PlacedOrderSnapshot snapshot = snapshot(servedGeohash7());

        PlanOrderUseCase.PlanOutcome outcome = planOrder.plan(snapshot, Ids.newId());
        assertThat(outcome.kind())
                .as("시드된 권역의 주소이므로 계획되어야 한다")
                .isEqualTo(PlanOrderUseCase.PlanOutcome.Kind.PLANNED);

        ConsumerRecord<String, String> record = awaitRecordFor(snapshot.orderId());

        // 봉투 + 페이로드 양쪽을 계약으로 검증한다. 단위 테스트는 페이로드만 본다.
        CONTRACTS.validateRecord(record.value());

        JsonNode envelope = CONTRACTS.json().readTree(record.value());
        assertThat(envelope.get("eventType").asString()).isEqualTo("fulfillment.planned");
        assertThat(envelope.get("producer").asString()).isEqualTo("fulfillment-service");
        assertThat(envelope.get("schemaVersion").intValue()).isEqualTo(1);

        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("outcome").asString()).isEqualTo("PLANNED");
        assertThat(payload.get("orderId").asString()).isEqualTo(snapshot.orderId().toString());
        assertThat(payload.get("waveId").isNull()).isFalse();
        assertThat(payload.get("promiseRevised").booleanValue()).isFalse();
        // §4.5 — 같은 주문의 이벤트는 같은 파티션으로 가야 order-service 가 보는 순서가 유지된다.
        assertThat(record.key()).isEqualTo(snapshot.orderId().toString());
    }

    @Test
    void 배차_불가도_같은_토픽으로_나간다() {
        // 배차하지 못한 것도 하류가 알아야 하는 사실이다. order-service 는 이것을 받아 주문을
        // FAILED 로 둔다 (§5.2 6단계).
        PlacedOrderSnapshot snapshot = snapshot("zzzzzbc");

        planOrder.plan(snapshot, Ids.newId());

        ConsumerRecord<String, String> record = awaitRecordFor(snapshot.orderId());
        CONTRACTS.validateRecord(record.value());

        JsonNode payload = CONTRACTS.json().readTree(record.value()).get("payload");
        assertThat(payload.get("outcome").asString()).isEqualTo("UNSERVICEABLE");
        assertThat(payload.get("reason").asString()).isEqualTo("NO_ZONE_MATCH");
        assertThat(payload.has("promiseRevised"))
                .as("배차되지 못한 주문에는 개정할 약속이 없다 (계약 README §4.5-1)")
                .isFalse();
    }

    /**
     * 시드된 권역 중 하나의 geohash7. 접미 두 자는 권역 키(앞 5자)에 영향을 주지 않는다.
     *
     * <p>계약 파일이 아니라 DB 에서 뽑는다 — 이 테스트의 관심은 <em>발행 경로</em>이고, 권역
     * 시드가 지오코더를 덮는지는 {@code ZoneSeedCoverageIT} 가 본다.
     */
    private String servedGeohash7() {
        // 접미는 geohash 알파벳(base32 — a·i·l·o 없음)이어야 한다. 계약이 그 패턴을 강제하고,
        // 처음에 "ab" 를 붙였다가 브로커에 도착한 뒤 스키마 검증에서 걸렸다.
        return anyZoneGeohash5() + "bc";
    }

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private String anyZoneGeohash5() {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> ((String) entityManager
                        .createNativeQuery("SELECT geohash5 FROM zones ORDER BY geohash5 LIMIT 1")
                        .getSingleResult()).strip());
    }

    private static PlacedOrderSnapshot snapshot(String geohash7) {
        // 나노초를 일부러 섞는다. 저장 정밀도로 잘리지 않으면 웨이브 자연키 조회가 어긋나고
        // 모든 주문이 promiseRevised=true 로 나간다 — CI(Linux)에서만 드러났던 결함이다.
        Instant cutoffAt = Instant.now().plus(Duration.ofHours(1)).plusNanos(123);
        return new PlacedOrderSnapshot(Ids.newId(), Ids.newId(), "SAME_DAY",
                new PlacedOrderSnapshot.Address("서울 강남구 테헤란로 1", "06236",
                        new GeoPoint(37.4979, 127.0276), geohash7),
                new TimeWindow(cutoffAt, cutoffAt.plus(Duration.ofHours(6))),
                new PlacedOrderSnapshot.Parcel(1200, 8000, false, false),
                List.of(new PlacedOrderSnapshot.Item("SKU-00001", 1)),
                Instant.now(), cutoffAt);
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
}
