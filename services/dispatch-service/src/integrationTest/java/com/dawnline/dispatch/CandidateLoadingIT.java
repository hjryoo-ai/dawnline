package com.dawnline.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.common.Ids;
import com.dawnline.dispatch.application.port.out.DispatchCandidateRepository;
import com.dawnline.dispatch.domain.CandidateStatus;
import com.dawnline.dispatch.domain.DispatchCandidate;
import com.dawnline.messaging.contract.EventContracts;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
    private static final EventContracts CONTRACTS = EventContracts.load();

    private static KafkaProducer<String, String> producer;

    static {
        createTopics(TOPIC);
    }

    @Autowired
    private DispatchCandidateRepository candidates;

    @Autowired
    private EntityManager entityManager;

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
        producer.send(new ProducerRecord<>(TOPIC, orderId.toString(), value));
        producer.flush();
    }

    /** 계약 예시에서 orderId 와 outcome 만 바꾼다 — 나머지는 계약이 보증한 모양 그대로다. */
    private static String planned(UUID orderId, String outcome) {
        var envelope = CONTRACTS.readTree(CONTRACTS.contractsDirectory()
                .resolve(java.nio.file.Path.of("examples", "fulfillment.planned.v1.example.json")));
        var payload = (tools.jackson.databind.node.ObjectNode) envelope.get("payload");
        payload.put("orderId", orderId.toString());
        payload.put("outcome", outcome);
        var root = (tools.jackson.databind.node.ObjectNode) envelope;
        root.put("eventId", Ids.newId().toString());
        root.put("partitionKey", orderId.toString());
        root.put("occurredAt", Instant.now().toString());
        return root.toString();
    }
}
