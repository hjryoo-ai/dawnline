package com.dawnline.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.common.Ids;
import com.dawnline.common.TimeWindow;
import com.dawnline.fulfillment.application.CloseDueWavesService;
import com.dawnline.fulfillment.application.FulfillmentMetrics;
import com.dawnline.fulfillment.application.port.out.FulfillmentEvents;
import com.dawnline.fulfillment.application.port.out.FulfillmentOrderRepository;
import com.dawnline.fulfillment.application.port.out.ReferenceData;
import com.dawnline.fulfillment.application.port.out.WaveLock;
import com.dawnline.fulfillment.application.port.out.WaveRepository;
import com.dawnline.fulfillment.domain.FulfillmentOrder;
import com.dawnline.fulfillment.domain.ServiceTier;
import com.dawnline.fulfillment.domain.Wave;
import com.dawnline.fulfillment.domain.WaveStatus;
import com.dawnline.messaging.contract.EventContracts;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 *   <li>스케줄러 인스턴스 둘이 동시에 돌아도 {@code wave.closed} 는 정확히 한 번 나간다</li>
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

    /**
     * 시드된 캠프 (R__seed_fulfillment). 임의 UUID 를 쓰면 마감이 캠프를 찾지 못해 실패한다 —
     * {@code wave.closed} 가 캠프 좌표를 실어야 하기 때문이다(하류의 라우트 출발 지점, §6.2).
     */
    private static final UUID SEEDED_CAMP =
            UUID.fromString("01a06edd-6c00-7000-8001-000000000001");
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

    @Autowired
    private FulfillmentEvents events;

    @Autowired
    private WaveLock lock;

    @Autowired
    private Clock clock;

    @Autowired
    private FulfillmentMetrics metrics;

    @Autowired
    private ReferenceData referenceData;

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
        Wave wave = Wave.open(Ids.newId(), SEEDED_CAMP, ServiceTier.SAME_DAY, cutoffAt);
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

        ConsumerRecord<String, String> record = awaitClosed(wave.id());
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

    // --- 이중 마감 (Phase 2 DoD) -----------------------------------------------
    //
    // 두 테스트는 서로 다른 방어를 증명한다. 세 번째 방어(FOR UPDATE + 상태 재확인)를
    // 일부러 부숴 보면 아래쪽만 빨개진다 — 위쪽은 Redis 락이 두 번째 인스턴스를 DB 앞에서
    // 돌려보내기 때문에 통과한다. 그래서 둘 다 필요하다: 위는 락이 실제로 도는 것을,
    // 아래는 락이 없어도 정확성이 유지되는 것을(불변규칙 7) 말한다. 한쪽만 두면 "통과했는데
    // 아무것도 증명하지 못하는 테스트" 가 하나 더 생긴다.

    @Test
    void 두_인스턴스가_동시에_돌아도_wave_closed_는_한_번만_나간다() {
        Wave wave = dueWave(3);

        List<Integer> closed = raceToClose(scheduler(lock), scheduler(lock));

        assertThat(closed).as("둘 중 하나만 마감한다").containsExactlyInAnyOrder(1, 0);
        assertThat(statusOf(wave.id())).isEqualTo(WaveStatus.CLOSED);
        assertThat(closedRecordsFor(wave.id()))
                .as("같은 웨이브의 wave.closed 가 두 번 나가면 하류가 두 번 계획한다").hasSize(1);
    }

    @Test
    void 락이_열려_있어도_이중_마감이_되지_않는다() {
        // RedisWaveLock 은 Redis 장애 때 fail-open 이다(불변규칙 7) — 즉 "둘 다 락을 얻은"
        // 이 상황은 가정이 아니라 Redis 가 죽은 날의 실제 모습이다. 정확성의 근거가 락이 아니라
        // FOR UPDATE 와 상태 전이라는 것을 여기서 증명한다.
        Wave wave = dueWave(3);
        WaveLock alwaysGrants = waveId -> Optional.<WaveLock.Guard>of(() -> { });

        List<Integer> closed = raceToClose(scheduler(alwaysGrants), scheduler(alwaysGrants));

        assertThat(closed).as("락이 아니라 DB 가 막는다").containsExactlyInAnyOrder(1, 0);
        assertThat(statusOf(wave.id())).isEqualTo(WaveStatus.CLOSED);
        assertThat(closedRecordsFor(wave.id())).hasSize(1);
    }

    /** 빈과 같은 협력자를 쓰되 락만 갈아 끼운 두 번째 인스턴스. */
    private CloseDueWavesService scheduler(WaveLock waveLock) {
        return new CloseDueWavesService(waves, orders, events, waveLock, transactionManager, clock,
                GRACE, 10, metrics, referenceData);
    }

    /**
     * 둘을 같은 순간에 출발시킨다. 순차로 부르면 경합이 없어 아무것도 증명하지 못한다.
     *
     * <p>공용 ForkJoin 풀을 쓰지 않는다 — 코어가 하나인 러너에서는 병렬도가 1 이라 장벽에서
     * 서로를 기다리다 멎는다. 이 테스트가 필요로 하는 것은 <em>진짜 스레드 둘</em>이다.
     */
    private List<Integer> raceToClose(CloseDueWavesService first, CloseDueWavesService second) {
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var left = CompletableFuture.supplyAsync(() -> closeAfter(start, first), pool);
            var right = CompletableFuture.supplyAsync(() -> closeAfter(start, second), pool);
            return List.of(left.join(), right.join());
        } finally {
            pool.shutdownNow();
        }
    }

    private static int closeAfter(CyclicBarrier start, CloseDueWavesService scheduler) {
        try {
            start.await();
        } catch (Exception e) {
            throw new IllegalStateException("동시 출발에 실패했습니다", e);
        }
        return scheduler.closeDue();
    }

    /**
     * 이 캠프의 {@code wave.closed} 를 모두 모은다. "하나 있다" 가 아니라 <strong>"하나뿐이다"</strong>
     * 를 봐야 하므로, 하나를 본 뒤에도 3초 더 폴링해 두 번째가 오지 않는 것을 확인한다.
     */
    private List<ConsumerRecord<String, String>> closedRecordsFor(UUID waveId) {
        List<ConsumerRecord<String, String>> seen = new ArrayList<>();
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            drainInto(seen, waveId);
            assertThat(seen).isNotEmpty();
        });
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            drainInto(seen, waveId);
            assertThat(seen).hasSizeLessThanOrEqualTo(1);
        });
        return seen;
    }

    private void drainInto(List<ConsumerRecord<String, String>> seen, UUID waveId) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
            if (isFor(record, waveId)) {
                seen.add(record);
            }
        }
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

    /**
     * 이 웨이브의 {@code wave.closed} 를 기다린다.
     *
     * <p><strong>키가 아니라 페이로드의 {@code waveId} 로 거른다.</strong> 키는 {@code campId}
     * 이고(§4.1) 이 클래스의 모든 테스트가 같은 시드 캠프를 쓰므로, 키로 거르면 앞 테스트의
     * 레코드가 섞인다 — {@code PlanExecutionIT} 에서 CI 가 잡은 것과 같은 함정이다.
     */
    private ConsumerRecord<String, String> awaitClosed(UUID waveId) {
        List<ConsumerRecord<String, String>> seen = new ArrayList<>();
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(200));
            polled.forEach(seen::add);
            assertThat(seen).anyMatch(record -> isFor(record, waveId));
        });
        return seen.stream().filter(record -> isFor(record, waveId)).findFirst().orElseThrow();
    }

    private static boolean isFor(ConsumerRecord<String, String> record, UUID waveId) {
        return waveId.toString().equals(
                CONTRACTS.json().readTree(record.value()).get("payload").get("waveId").asString());
    }
}
