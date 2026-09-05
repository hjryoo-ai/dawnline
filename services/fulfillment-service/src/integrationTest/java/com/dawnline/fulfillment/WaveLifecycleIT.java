package com.dawnline.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.fulfillment.application.CloseDueWavesService;
import com.dawnline.fulfillment.application.port.out.FulfillmentOrderRepository;
import com.dawnline.fulfillment.application.port.out.WaveRepository;
import com.dawnline.fulfillment.domain.FulfillmentOrder;
import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.Wave;
import com.dawnline.fulfillment.domain.WaveStatus;
import com.dawnline.messaging.contract.EventContracts;
import jakarta.persistence.EntityManager;
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
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 웨이브 수명주기 전체 — 마감부터 계획 결과까지 (§5.2, ADR-024·025).
 *
 * <p>Phase 2-5 마감 대조표의 네 열을 여기서 채운다.
 *
 * <ol>
 *   <li>{@code wave.closed} 가 브로커에 도착하고 봉투까지 계약을 지킨다 (키는 {@code campId})</li>
 *   <li>{@code plan.completed} 소비 → {@code CLOSED → PLANNED}</li>
 *   <li>{@code plan.failed} 소비 → {@code CLOSED → PLAN_FAILED}, 재실행 → {@code PLANNED}</li>
 *   <li>이미 {@code PLANNED} 인 웨이브에 늦게 온 {@code plan.failed} 는 무시된다</li>
 * </ol>
 *
 * <p>계획 결과 두 이벤트의 발행자는 Phase 3 의 dispatch-service 다. 계약을 소비자인 이쪽이 먼저
 * 정의했으므로(ADR-024 결정 5) 예시 이벤트를 브로커에 직접 넣어 지금 완결한다.
 */
@SpringBootTest(classes = FulfillmentApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("WaveLifecycleIT — 마감과 계획 결과")
class WaveLifecycleIT extends FulfillmentIntegrationTestBase {

    private static final String WAVE_CLOSED_TOPIC = "dawnline.wave.closed.v1";
    private static final String PLAN_COMPLETED_TOPIC = "dawnline.plan.completed.v1";
    private static final String PLAN_FAILED_TOPIC = "dawnline.plan.failed.v1";
    private static final EventContracts CONTRACTS = EventContracts.load();

    private static final Instant CUTOFF = Instant.parse("2026-09-06T01:00:00Z");
    private static final Duration GRACE = Duration.ofSeconds(90);

    private static KafkaConsumer<String, String> consumer;
    private static KafkaProducer<String, String> producer;

    static {
        createTopics(WAVE_CLOSED_TOPIC, PLAN_COMPLETED_TOPIC, PLAN_FAILED_TOPIC,
                "dawnline.order.placed.v1", "dawnline.order.cancelled.v1",
                "dawnline.fulfillment.planned.v1");
    }

    @Autowired
    private WaveRepository waves;

    @Autowired
    private FulfillmentOrderRepository orders;

    @Autowired
    private CloseDueWavesService closeDueWaves;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeAll
    static void connect() {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "wave-lifecycle-it-" + Ids.newId(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        consumer.subscribe(List.of(WAVE_CLOSED_TOPIC));
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()));
    }

    @AfterAll
    static void disconnect() {
        consumer.close();
        producer.close();
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void clean() {
        tx().executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM fulfillment_orders").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM waves").executeUpdate();
        });
    }

    /** 마감 시각이 이미 지난 웨이브를 만든다. 스케줄러가 아니라 테스트가 직접 부른다. */
    private Wave dueWave(int orderCount) {
        Instant cutoffAt = Instant.now().minus(GRACE).minus(Duration.ofMinutes(5))
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Wave wave = Wave.open(Ids.newId(), Ids.newId(), ServiceTier.SAME_DAY, cutoffAt);
        tx().executeWithoutResult(status -> waves.insertIfAbsent(wave));
        for (int i = 0; i < orderCount; i++) {
            tx().executeWithoutResult(status -> orders.insertIfAbsent(FulfillmentOrder.planned(
                    Ids.newId(), Ids.newId(), wave.id(), wave.campId(), Ids.newId(), Ids.newId(),
                    cutoffAt, new TimeWindow(CUTOFF, CUTOFF.plusSeconds(3600)), false, null, CUTOFF)));
        }
        return wave;
    }

    private WaveStatus statusOf(UUID waveId) {
        return tx().execute(status -> waves.findById(waveId).orElseThrow().status());
    }

    // --- 마감 -----------------------------------------------------------------

    @Test
    void 마감이_주문을_세어_wave_closed_를_브로커로_보낸다() {
        Wave wave = dueWave(3);

        assertThat(closeDueWaves.closeDue()).isEqualTo(1);
        assertThat(statusOf(wave.id())).isEqualTo(WaveStatus.CLOSED);

        ConsumerRecord<String, String> record = awaitClosed(wave.campId());
        CONTRACTS.validateRecord(record.value());

        JsonNode envelope = CONTRACTS.json().readTree(record.value());
        assertThat(envelope.get("eventType").asString()).isEqualTo("wave.closed");
        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("waveId").asString()).isEqualTo(wave.id().toString());
        assertThat(payload.get("orderCount").intValue())
                .as("마감 시점의 집계값이다 (ADR-025)").isEqualTo(3);
        // §4.5 — 키가 campId 라 같은 캠프의 웨이브 계획이 직렬화된다.
        assertThat(record.key()).isEqualTo(wave.campId().toString());
    }

    @Test
    void 취소된_주문은_마감_카운트에서_빠진다() {
        Wave wave = dueWave(2);
        UUID cancelled = tx().execute(status ->
                orders.findPlannedInWave(wave.id()).getFirst().orderId());
        tx().executeWithoutResult(status -> {
            FulfillmentOrder order = orders.findById(cancelled).orElseThrow();
            order.cancel(Instant.now());
            orders.update(order);
        });

        closeDueWaves.closeDue();

        Integer counted = tx().execute(status -> waves.findById(wave.id()).orElseThrow().orderCount());
        assertThat(counted)
                .as("취소가 카운트를 건드리는 분기가 없어도 맞는다 (ADR-025)").isEqualTo(1);
    }

    // --- 계획 결과 -------------------------------------------------------------

    @Test
    void plan_completed_로_PLANNED_가_된다() {
        // 이 전이가 발화해야 ADR-023 의 정리 배치가 PLANNED 주문 행을 지울 수 있다.
        Wave wave = dueWave(1);
        closeDueWaves.closeDue();

        publish(PLAN_COMPLETED_TOPIC, wave.id(), planCompleted(wave));

        awaitStatus(wave.id(), WaveStatus.PLANNED);
    }

    @Test
    void plan_failed_로_PLAN_FAILED_가_되고_재실행으로_되살아난다() {
        Wave wave = dueWave(1);
        closeDueWaves.closeDue();

        publish(PLAN_FAILED_TOPIC, wave.id(), planFailed(wave));
        awaitStatus(wave.id(), WaveStatus.PLAN_FAILED);

        // §5.3 이 적어 둔 "운영자 재실행 가능" 이 돌아올 자리다 (ADR-024 결정 3).
        publish(PLAN_COMPLETED_TOPIC, wave.id(), planCompleted(wave));
        awaitStatus(wave.id(), WaveStatus.PLANNED);
    }

    @Test
    void 계획된_웨이브에_늦게_온_plan_failed_는_무시된다() {
        // 두 이벤트는 다른 토픽이라 재실행 시 순서가 뒤바뀔 수 있다. 그대로 두면 라우트가 이미
        // 나간 웨이브가 실패로 표시된다 (ADR-024 결정 4).
        Wave wave = dueWave(1);
        closeDueWaves.closeDue();
        publish(PLAN_COMPLETED_TOPIC, wave.id(), planCompleted(wave));
        awaitStatus(wave.id(), WaveStatus.PLANNED);

        publish(PLAN_FAILED_TOPIC, wave.id(), planFailed(wave));

        // 상태가 바뀌지 않는 것을 증명하려면 기다려야 한다 — 소비될 시간을 준 뒤에도 그대로여야 한다.
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(statusOf(wave.id())).isEqualTo(WaveStatus.PLANNED));
    }

    // --- 도우미 ----------------------------------------------------------------

    private void publish(String topic, UUID waveId, String value) {
        producer.send(new ProducerRecord<>(topic, waveId.toString(), value));
        producer.flush();
    }

    private static String planCompleted(Wave wave) {
        return envelope("plan.completed", wave.id(), """
                {"planId":"%s","waveId":"%s","campId":"%s","strategy":"sweep-greedy-nn+ls",
                 "mode":"FULL","routeCount":2,"assignedCount":1,"unassignedCount":0,
                 "totalCostKrw":138400,"planDurationMs":1200}"""
                .formatted(Ids.newId(), wave.id(), wave.campId()));
    }

    private static String planFailed(Wave wave) {
        return envelope("plan.failed", wave.id(), """
                {"planId":"%s","waveId":"%s","campId":"%s","reason":"TIMEOUT",
                 "failedAt":"2026-09-06T01:05:00Z"}"""
                .formatted(Ids.newId(), wave.id(), wave.campId()));
    }

    /** 봉투로 감싼다. 계약 테스트가 검사하는 것과 같은 모양이어야 한다 (계약 README §2). */
    private static String envelope(String eventType, UUID waveId, String payload) {
        return """
                {"eventId":"%s","eventType":"%s","schemaVersion":1,"occurredAt":"%s",
                 "producer":"dispatch-service","partitionKey":"%s","payload":%s}"""
                .formatted(Ids.newId(), eventType, Instant.now(), waveId, payload);
    }

    private void awaitStatus(UUID waveId, WaveStatus expected) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(statusOf(waveId)).isEqualTo(expected));
    }

    private ConsumerRecord<String, String> awaitClosed(UUID campId) {
        List<ConsumerRecord<String, String>> seen = new ArrayList<>();
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(200));
            polled.forEach(seen::add);
            assertThat(seen).anyMatch(record -> campId.toString().equals(record.key()));
        });
        return seen.stream().filter(record -> campId.toString().equals(record.key()))
                .findFirst().orElseThrow();
    }
}
