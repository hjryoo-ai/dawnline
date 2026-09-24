package com.dawnline.messagingtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dawnline.common.Ids;
import com.dawnline.messaging.Topics;
import com.dawnline.messaging.outbox.OutboxAppender;
import com.dawnline.messaging.outbox.OutboxEvent;
import com.dawnline.messaging.outbox.OutboxMessage;
import com.dawnline.messaging.outbox.OutboxRepository;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 발행 측 격리 (DESIGN.md §4.6, ADR-015) — 실제 PostgreSQL 18 + Kafka 4.3.
 *
 * <p>인메모리 가짜로는 이 경로의 핵심을 검증할 수 없다. 격리의 정확성은
 * {@code WHERE published_at IS NULL AND failed_at IS NULL} 이 <strong>실제 SQL 과 부분 인덱스에서</strong>
 * 격리 행을 빼는지에 달려 있고, 복구 절차(RB-05)도 실제 {@code UPDATE} 다.
 *
 * <p>이 클래스는 <strong>전용 데이터베이스</strong>를 쓴다. 릴레이가 100ms 마다 모든 미발행 행을
 * 집으므로, DB 를 공유하면 다른 테스트 클래스의 행을 발행해 버리고 전역 집계도 서로 오염된다
 * ({@link MessagingIntegrationTestBase#createIsolatedDatabase} 참고).
 *
 * <p>{@code max.block.ms} 를 줄인 이유: 없는 토픽으로 보낼 때 프로듀서는 기본 60초 동안 메타데이터를
 * 기다린다. 그 60초는 릴레이 스레드를 잡고 있어 테스트가 그만큼 느려진다. 짧게 잡으면 같은
 * 일시적 실패({@code TimeoutException})가 몇 초 안에 난다.
 */
@SpringBootTest(
        classes = MessagingTestApplication.class,
        properties = {
            "spring.kafka.producer.properties.max.block.ms=3000",
            "spring.kafka.producer.properties.delivery.timeout.ms=5000",
            "spring.kafka.producer.properties.request.timeout.ms=2000",
            "dawnline.messaging.outbox.send-timeout=10s",
        })
@AutoConfigureMockMvc
class OutboxQuarantineIT extends MessagingIntegrationTestBase {

    /**
     * 테스트 페이로드.
     *
     * @param orderId 주문 id
     */
    record OrderPlaced(String orderId) {
    }

    /**
     * DB 뿐 아니라 <strong>토픽도</strong> 다른 IT 와 겹치지 않게 고른다. 겹치면 이 테스트가 발행한
     * 레코드를 다른 테스트의 컨슈머가 처음부터 읽어 건수 어설션이 깨진다.
     * (§4.1 에 있는 실제 토픽만 쓴다 — 설계서에 없는 토픽은 만들지 않는다.)
     */
    private static final String TOPIC = Topics.forEvent("fulfillment.planned", 1);

    /** 이 토픽은 <strong>일부러 만들지 않는다</strong>. 일시적 실패를 만드는 데 쓴다. */
    private static final String MISSING_TOPIC = Topics.forEvent("order.cancelled", 1);

    private static final String DATABASE = "dawnline_quarantine";

    static {
        createTopic(TOPIC, 3);
    }

    /**
     * 전용 DB 로 갈아탄다. 부모의 {@code @DynamicPropertySource} 가 먼저 등록한 값을 덮어쓴다.
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        useIsolatedDatabase(registry, DATABASE);
    }

    @Autowired
    private OutboxAppender appender;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MockMvc mvc;

    private TransactionTemplate transactions() {
        return new TransactionTemplate(transactionManager);
    }

    @Test
    void 맨_앞의_독약_행은_격리되고_뒤의_행은_계속_발행된다() {
        clearOutbox();
        // 봉투로 만들 수 없는 행. 쓰기 경로의 가드를 우회해 직접 넣는다 —
        // 과거에 느슨한 규칙으로 들어왔거나 수동 INSERT 된 행을 흉내 낸다.
        UUID poisonId = insertPoisonRow();
        UUID firstId = append("fulfillment.planned");
        UUID secondId = append("fulfillment.planned");

        // 예전 정책이라면 여기서 published 가 영원히 0이다(head-of-line blocking).
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(isPublished(firstId)).as("독약 행 뒤의 첫 행이 발행돼야 한다").isTrue();
            assertThat(isPublished(secondId)).as("독약 행 뒤의 둘째 행이 발행돼야 한다").isTrue();
        });

        OutboxEvent poison = find(poisonId);
        assertThat(poison.isQuarantined()).as("독약 행은 격리돼야 한다").isTrue();
        assertThat(poison.isPublished()).isFalse();
        assertThat(poison.publishAttempts()).isPositive();

        // 게이지 두 개가 겹치지 않는다 (§9.1).
        assertThat(inTransaction(outboxRepository::countFailed)).isEqualTo(1L);
        assertThat(inTransaction(outboxRepository::countUnpublished))
                .as("격리 행은 미발행 집계에서 빠진다").isZero();

        // 격리 행은 다시 집히지 않는다 — attempts 가 계속 오르면 릴레이가 또 집고 있다는 뜻이다.
        short attemptsAfterQuarantine = poison.publishAttempts();
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(find(poisonId).publishAttempts()).isEqualTo(attemptsAfterQuarantine));
    }

    @Test
    void 브로커가_받아주지_않으면_격리하지_않고_복구_후_전량_발행한다() {
        clearOutbox();
        // 토픽이 없다(자동 생성 꺼짐) → UnknownTopicOrPartitionException = 일시적 실패.
        // 여기서 격리하면 브로커가 잠깐 흔들렸다는 이유로 멀쩡한 이벤트가 사람 손을 기다리게 된다.
        UUID firstId = append("order.cancelled");
        UUID secondId = append("order.cancelled");

        // 실제로 시도가 일어났는지부터 확인한다. 시도조차 없었다면 이 테스트는 아무것도 증명하지 못한다.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(find(firstId).publishAttempts()).as("재시도 횟수가 기록돼야 한다").isPositive());

        // 그리고 그 실패는 격리로 이어지면 안 된다.
        assertThat(inTransaction(outboxRepository::countFailed))
                .as("일시적 실패는 격리하지 않는다").isZero();
        assertThat(isPublished(firstId)).isFalse();
        assertThat(inTransaction(outboxRepository::countUnpublished))
                .as("미발행으로 남아 있어야 다음 폴링에 재시도된다").isEqualTo(2L);

        // 복구: 토픽을 만든다.
        createTopic(MISSING_TOPIC, 3);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(isPublished(firstId)).isTrue();
            assertThat(isPublished(secondId)).isTrue();
        });
        assertThat(inTransaction(outboxRepository::countFailed)).isZero();
    }

    @Test
    void 격리를_해제하면_다시_발행된다() throws Exception {
        clearOutbox();
        UUID poisonId = insertPoisonRow();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(find(poisonId).isQuarantined()).isTrue());

        // RB-05 복구 절차의 두 절반. (a) 원인 수정은 사람의 SQL 이다 — 운영에서는 고치는 방법이 행마다 다르고,
        // 여기서는 봉투가 만들어지도록 eventType 을 바로잡는다. 격리는 아직 그대로다.
        transactions().executeWithoutResult(status -> entityManager.createNativeQuery("""
                UPDATE outbox_events
                   SET event_type = 'fulfillment.planned',
                       headers = '{"eventType":"fulfillment.planned","schemaVersion":"1"}'::jsonb
                 WHERE id = :id
                """).setParameter("id", poisonId).executeUpdate());
        assertThat(find(poisonId).isQuarantined()).as("전제 — 원인 수정만으로는 격리가 풀리지 않는다").isTrue();

        // (b) 격리 해제는 엔드포인트다 (§4.6 「격리 조회·재큐 엔드포인트」).
        mvc.perform(post("/api/v1/admin/outbox/{id}/requeue", poisonId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(poisonId.toString()))
                .andExpect(jsonPath("$.eventType").value("fulfillment.planned"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(isPublished(poisonId)).as("격리를 풀면 릴레이가 다시 집어야 한다").isTrue());
        assertThat(inTransaction(outboxRepository::countFailed)).isZero();

        // 응답을 못 받은 운영자가 다시 누른다 — 409 가 앞의 요청이 적용됐고 행이 이미 나갔다고 말한다
        // (ADR-015 후속 정정 결정 3, ops-api 감사 UNKNOWN 의 해소).
        mvc.perform(post("/api/v1/admin/outbox/{id}/requeue", poisonId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not-quarantined"))
                .andExpect(jsonPath("$.currentState").value("PUBLISHED"))
                .andExpect(jsonPath("$.publishedAt").isNotEmpty());
    }

    @Test
    void 목록은_격리된_행만_격리_시각_순으로_싣고_payload_와_headers_를_싣지_않는다() throws Exception {
        clearOutbox();
        UUID first = insertPoisonRow();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(find(first).isQuarantined()).isTrue());
        UUID second = insertPoisonRow();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(find(second).isQuarantined()).isTrue());
        UUID healthy = append("fulfillment.planned");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(isPublished(healthy)).isTrue());

        // JPQL 생성자 식과 SMALLINT → int 변환이 실제 PostgreSQL 에서 도는지가 여기서 드러난다.
        mvc.perform(get("/api/v1/admin/outbox/quarantined"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].id").value(first.toString()))
                .andExpect(jsonPath("$.events[1].id").value(second.toString()))
                .andExpect(jsonPath("$.events[0].eventType").value("FulfillmentPlanned"))
                .andExpect(jsonPath("$.events[0].publishAttempts").value(1))
                .andExpect(jsonPath("$.events[0].failedAt").isNotEmpty())
                .andExpect(jsonPath("$.events[0].payload").doesNotExist())
                .andExpect(jsonPath("$.events[0].headers").doesNotExist());

        mvc.perform(get("/api/v1/admin/outbox/quarantined").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.events.length()").value(1));
    }

    @Test
    void 격리되지_않은_행의_재큐는_409_이고_그_행을_건드리지_않는다() throws Exception {
        clearOutbox();
        // 일시적 실패를 두 번 겪고 나간 행 — 시도 횟수는 진단 기록이다. 조건부 UPDATE 의 `failed_at IS NOT NULL` 이
        // 빠지면 이 행이 풀리면서 published_at 은 그대로, publish_attempts 만 0 이 된다.
        UUID id = Ids.newId();
        Instant publishedAt = Instant.parse("2026-09-24T00:00:00Z");
        transactions().executeWithoutResult(status -> {
            OutboxEvent row = new OutboxEvent(id, "Order", UUID.randomUUID(), "fulfillment.planned", TOPIC,
                    id.toString(), "{\"eventType\":\"fulfillment.planned\",\"schemaVersion\":\"1\"}",
                    "{\"orderId\":\"" + id + "\"}", publishedAt.minusSeconds(60));
            row.recordFailedAttempt();
            row.recordFailedAttempt();
            row.markPublished(publishedAt);
            outboxRepository.append(row);
        });

        mvc.perform(post("/api/v1/admin/outbox/{id}/requeue", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not-quarantined"))
                .andExpect(jsonPath("$.currentState").value("PUBLISHED"))
                .andExpect(jsonPath("$.publishedAt").value(publishedAt.toString()));

        OutboxEvent after = find(id);
        assertThat(after.isPublished()).isTrue();
        assertThat(after.publishAttempts()).as("진단 기록을 지우지 않는다").isEqualTo((short) 2);
    }

    @Test
    void 없는_행의_재큐는_404_다() throws Exception {
        mvc.perform(post("/api/v1/admin/outbox/{id}/requeue", Ids.newId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"));
    }

    /** 봉투 형식을 어기는 {@code event_type} 을 가진 행. 릴레이의 조립 단계에서 터진다. */
    private UUID insertPoisonRow() {
        UUID id = Ids.newId();
        transactions().executeWithoutResult(status -> outboxRepository.append(new OutboxEvent(
                id, "Order", UUID.randomUUID(), "FulfillmentPlanned", TOPIC, id.toString(),
                "{\"eventType\":\"FulfillmentPlanned\",\"schemaVersion\":\"1\"}",
                "{\"orderId\":\"" + id + "\"}", Instant.now())));
        return id;
    }

    private UUID append(String eventType) {
        UUID orderId = Ids.newId();
        return transactions().execute(status -> appender.append(OutboxMessage.keyedByAggregate(
                "Order", orderId, eventType, 1, new OrderPlaced(orderId.toString()))));
    }

    private boolean isPublished(UUID id) {
        return find(id).isPublished();
    }

    private OutboxEvent find(UUID id) {
        return transactions().execute(status -> {
            entityManager.clear();
            return entityManager.find(OutboxEvent.class, id);
        });
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transactions().execute(status -> work.get());
    }

    /** 릴레이가 계속 돌고 있으므로 테스트마다 깨끗한 상태에서 시작한다. */
    private void clearOutbox() {
        transactions().executeWithoutResult(status ->
                entityManager.createNativeQuery("DELETE FROM outbox_events").executeUpdate());
        List<OutboxEvent> remaining = transactions().execute(status ->
                entityManager.createQuery("SELECT e FROM OutboxEvent e", OutboxEvent.class).getResultList());
        assertThat(remaining).isEmpty();
    }
}
