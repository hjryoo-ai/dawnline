package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.common.Ids;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.domain.CandidateStatus;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.messaging.contract.EventContracts;
import com.dawnline.messaging.json.EventJson;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code fulfillment.planned} 를 <strong>실제 브로커로</strong> 보내 후보가 적재되는지.
 *
 * <p>페이로드 매핑은 단위 테스트가 계약 예시로 보고, 여기서는 <em>그 사이</em>를 본다 — 봉투
 * 역직렬화, 멱등 게이트, 트랜잭션. Phase 1 의 {@code OrderProgressListenerIT} 와 같은 형태다.
 */
@SpringBootTest(classes = DispatchApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("CandidateLoadingIT — fulfillment.planned 소비")
class CandidateLoadingIT extends DispatchIntegrationTestBase {

    /**
     * 릴레이를 끈다 — 이 클래스는 발행을 보지 않는다.
     *
     * <p>끄는 것이 <strong>격리</strong>다. 리더 락이 advisory lock 이 된 뒤(ADR-027 후속 정정)
     * 이 컨테이너의 한 데이터베이스에 대해 릴레이는 <em>한 컨텍스트만</em> 리더가 된다. 스프링은
     * 컨텍스트를 캐시하므로 먼저 뜬 클래스의 릴레이가 락을 계속 쥐고, 그러면 실제로 발행을 보는
     * {@code PlanExecutionIT} 가 팔로워가 되어 아무것도 못 본다. 순서에 달린 실패다.
     *
     * <p>이전에는 이 문제가 보이지 않았다 — 리더 락이 Redis 였고 이 컨텍스트들에는 Redis 가
     * 없어서 전부 판정 불가(발행 안 함)였기 때문이다. <strong>격리가 락의 무력함에 기대고
     * 있었다.</strong>
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void relayOff(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    private static final String TOPIC = "dawnline.fulfillment.planned.v1";
    private static final String CANCELLED_TOPIC = "dawnline.order.cancelled.v1";
    private static final EventContracts CONTRACTS = EventContracts.load();

    private static KafkaProducer<String, String> producer;

    static {
        createTopics(TOPIC, CANCELLED_TOPIC);
    }

    @Autowired
    private DispatchCandidateRepository candidates;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EventJson json;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeAll
    static void connect() {
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()));
    }

    @AfterAll
    static void disconnect() {
        producer.close();
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void clean() {
        tx().executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM dispatch_candidates").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM processed_events").executeUpdate();
        });
    }

    @Test
    void 계획된_주문이_후보로_적재된다() {
        UUID orderId = Ids.newId();

        publish(orderId, planned(orderId, "PLANNED"));

        awaitCandidate(orderId);
        assertThat(findById(orderId).orElseThrow().status()).isEqualTo(CandidateStatus.PENDING);
    }

    @Test
    void 배차_불가는_후보가_되지_않는다() {
        // fulfillment 가 이미 내린 정상 판정이다 — dispatch 에게는 계획할 것이 없다.
        UUID unserviceable = Ids.newId();
        UUID planned = Ids.newId();

        publish(unserviceable, planned(unserviceable, "UNSERVICEABLE"));
        publish(planned, planned(planned, "PLANNED"));

        // 뒤에 보낸 것이 도착했다는 사실이 앞의 것도 처리될 시간이 지났다는 뜻이다.
        awaitCandidate(planned);
        Optional<DispatchCandidate> found = findById(unserviceable);
        assertThat(found).isEmpty();
    }

    @Test
    void 같은_이벤트를_두_번_보내도_한_번만_적재된다() {
        UUID orderId = Ids.newId();
        String event = planned(orderId, "PLANNED");

        publish(orderId, event);
        publish(orderId, event);

        awaitCandidate(orderId);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(count("dispatch_candidates")).isEqualTo(1L));
    }

    @Test
    void 개정된_약속은_우선도로_남는다() {
        // ADR-028 — 우선도는 계약의 필드가 아니라 계약의 <em>사실</em>에서 나온다.
        // 브로커까지 도는 경로에서 그 파생이 실제로 일어나는지 본다: 단위 테스트는 점수표를,
        // 계약 테스트는 매핑을 보지만, 둘을 잇는 배선이 빠져도 각각은 통과한다.
        UUID plain = Ids.newId();
        UUID revised = Ids.newId();

        publish(plain, planned(plain, "PLANNED", false));
        publish(revised, planned(revised, "PLANNED", true));

        awaitCandidate(plain);
        awaitCandidate(revised);
        assertThat(findById(plain).orElseThrow().priority())
                .as("전제: 개정되지 않은 주문은 0 이어야 한다 — 아니면 아래 2 는 우연일 수 있다")
                .isZero();
        assertThat(findById(revised).orElseThrow())
                .satisfies(candidate -> {
                    assertThat(candidate.promiseRevised()).as("근거를 함께 남긴다").isTrue();
                    assertThat(candidate.priority())
                            .as("promiseRevised 가중치 +2 (dawnline.dispatch.priority)")
                            .isEqualTo(2);
                });
    }

    @Test
    void 취소가_먼저_오면_뒤에_온_fulfillment_planned_는_후보를_되살리지_않는다() {
        // ADR-074 — turbulent 의 12건. 후보는 fulfillment.planned 가, 취소는 order.cancelled 가 가져오고 두 토픽 사이의 순서는
        // 보장되지 않는다(§4.5). 취소를 먼저 처리시킨 뒤에 적재를 보내 그 순서를 고정한다.
        UUID orderId = Ids.newId();
        String cancel = cancelled(orderId);
        String load = planned(orderId, "PLANNED");

        publish(CANCELLED_TOPIC, orderId, cancel);
        awaitProcessed(cancel);
        assertThat(statusOf(orderId)).as("전제 — 적재를 보내기 전이다. 취소만 처리했다").isNotEqualTo(Optional.of("PENDING"));
        publish(TOPIC, orderId, load);
        awaitProcessed(load);

        assertThat(statusOf(orderId))
                .as("취소된 주문은 후보가 되지 않는다 — PENDING 이면 다음 계획이 집고 기사가 배송한다")
                .contains("CANCELLED");
    }

    @Test
    void 적재가_먼저_와도_결과는_같다() {
        // 위의 역순 — §6.10 의 「후보, 계획 전」 행이다. 두 순서의 끝이 같아야 순서가 결과를 정하지 않는다.
        UUID orderId = Ids.newId();
        String load = planned(orderId, "PLANNED");
        String cancel = cancelled(orderId);

        publish(TOPIC, orderId, load);
        awaitProcessed(load);
        publish(CANCELLED_TOPIC, orderId, cancel);
        awaitProcessed(cancel);

        assertThat(statusOf(orderId)).contains("CANCELLED");
    }

    private void awaitProcessed(String event) {
        UUID eventId = json.readEnvelope(event).eventId();
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(processedCount(eventId))
                        .as("dispatch 가 %s 를 처리했다", eventId).isEqualTo(1L));
    }

    private long processedCount(UUID eventId) {
        Long count = tx().execute(status -> ((Number) entityManager
                .createNativeQuery("SELECT count(*) FROM processed_events WHERE event_id = ?")
                .setParameter(1, eventId).getSingleResult()).longValue());
        return Objects.requireNonNull(count, "count");
    }

    /** 행의 상태를 그대로 — 저장소를 거치지 않는다(표식은 스냅샷이 없어 도메인으로 되살리지 않는다, ADR-074 결정 3). */
    private Optional<String> statusOf(UUID orderId) {
        List<?> rows = tx().execute(status -> entityManager
                .createNativeQuery("SELECT status FROM dispatch_candidates WHERE order_id = ?")
                .setParameter(1, orderId).getResultList());
        return Objects.requireNonNull(rows, "rows").stream().map(String.class::cast).findFirst();
    }

    private void awaitCandidate(UUID orderId) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(findById(orderId)).isPresent());
    }

    private Optional<DispatchCandidate> findById(UUID orderId) {
        return tx().execute(status -> candidates.findById(orderId));
    }

    private long count(String table) {
        return tx().execute(status -> ((Number) entityManager
                .createNativeQuery("SELECT count(*) FROM " + table).getSingleResult()).longValue());
    }

    private void publish(UUID orderId, String value) {
        publish(TOPIC, orderId, value);
    }

    private void publish(String topic, UUID orderId, String value) {
        producer.send(new ProducerRecord<>(topic, orderId.toString(), value));
        producer.flush();
    }

    /** 계약 예시에서 orderId 와 eventId 만 바꾼다. */
    private static String cancelled(UUID orderId) {
        var envelope = (tools.jackson.databind.node.ObjectNode) CONTRACTS.readTree(CONTRACTS.contractsDirectory()
                .resolve(java.nio.file.Path.of("examples", "order.cancelled.v1.example.json")));
        ((tools.jackson.databind.node.ObjectNode) envelope.get("payload")).put("orderId", orderId.toString());
        envelope.put("eventId", Ids.newId().toString());
        envelope.put("partitionKey", orderId.toString());
        return envelope.toString();
    }

    private static String planned(UUID orderId, String outcome) {
        return planned(orderId, outcome, false);
    }

    /** 계약 예시에서 orderId·outcome·promiseRevised 만 바꾼다 — 나머지는 계약이 보증한 모양이다. */
    private static String planned(UUID orderId, String outcome, boolean promiseRevised) {
        var envelope = CONTRACTS.readTree(CONTRACTS.contractsDirectory()
                .resolve(java.nio.file.Path.of("examples", "fulfillment.planned.v1.example.json")));
        var payload = (tools.jackson.databind.node.ObjectNode) envelope.get("payload");
        payload.put("orderId", orderId.toString());
        payload.put("outcome", outcome);
        payload.put("promiseRevised", promiseRevised);
        var root = (tools.jackson.databind.node.ObjectNode) envelope;
        root.put("eventId", Ids.newId().toString());
        root.put("partitionKey", orderId.toString());
        root.put("occurredAt", Instant.now().toString());
        return root.toString();
    }
}
