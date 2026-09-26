package com.dawnline.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.common.Ids;
import com.dawnline.messaging.EventHeaders;
import com.dawnline.messaging.contract.EventContracts;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
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
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 24시간 넘은 {@code order.placed} 가 <strong>재처리 경로로</strong> 다시 와도 오늘의 약속이 나가지 않는다 — {@code STALE_PLACED}
 * (DESIGN.md §5.2, ADR-020 후속 정정 · ADR-053, IMPLEMENTATION_PLAN 7-0 A9).
 *
 * <p>판정 자체는 순수 함수이고 단위 테스트가 경계를 본다({@code FcSelectionTest}). 여기서 보는 것은 <em>그 사이</em>다 — DLQ 재처리가
 * 만드는 레코드 모양(같은 바이트 · {@code kafka_dlt-*} 없음 · {@link EventHeaders#REPLAY_FOR} 가 이 그룹)이 브로커를 지나
 * {@code ReplayTargetFilter} → 멱등 게이트 → 계획까지 가서 {@code STALE_PLACED} 로 끝나는가. 레코드 모양은 ops-api 의
 * {@code KafkaDeadLetters.republish} 와 같다(그 IT 가 모양을 본다 — {@code DlqReplayIT}).
 *
 * <p>시각은 리터럴이 아니라 주입된 시계에서 뽑는다 — 판정이 {@code clock.instant()} 와 컷오프를 견주기 때문이다(CLAUDE.md). 계약 예시의
 * {@code cutoffAt}(2026-08-30)을 그대로 쓰면 이 테스트는 작성한 날로부터 유효 기간이 생긴다.
 */
@SpringBootTest(classes = FulfillmentApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("StalePlacedReplayIT — 재처리로 다시 온 하루 넘은 order.placed")
class StalePlacedReplayIT extends FulfillmentIntegrationTestBase {

    /**
     * 릴레이를 끈다 — 이 클래스는 발행을 보지 않는다(fulfillment.planned 는 outbox 행으로 본다).
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void relayOff(DynamicPropertyRegistry registry) {
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    private static final String ORDER_PLACED = "dawnline.order.placed.v1";
    private static final EventContracts CONTRACTS = EventContracts.load();
    private static final String EXAMPLE_EVENT_ID = "01a04dad-80da-79a6-95d0-ba4369830bdf";
    private static final String EXAMPLE_ORDER_ID = "01a04dad-80da-7f6e-a63a-e91c103516b0";

    private static KafkaProducer<String, String> producer;

    static {
        createTopics(ORDER_PLACED, "dawnline.order.cancelled.v1");
    }

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    private String group;

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

    @BeforeEach
    void groupOfThisService() {
        // 재처리가 지목하는 값은 리스너 컨테이너의 그룹 id 다 — 설정에서 읽는다(상수를 옮겨 적으면 둘이 갈라질 수 있다).
        group = environment.getRequiredProperty("spring.kafka.consumer.group-id");
    }

    @Test
    void 하루_넘은_컷오프로_다시_온_주문은_STALE_PLACED_로_끝나고_웨이브에_들지_않는다() {
        UUID orderId = Ids.newId();
        UUID eventId = Ids.newId();

        replay(orderId, eventId, clock.instant().minus(Duration.ofHours(25)), group);

        Map<String, Object> row = awaitOrder(orderId);
        assertThat(row.get("status")).isEqualTo("UNSERVICEABLE");
        assertThat(row.get("unserviceable_reason")).isEqualTo("STALE_PLACED");
        assertThat(row.get("wave_id")).as("오늘 웨이브에 편입되지 않는다 — 새 약속이 나가지 않는다").isNull();
        assertThat(planned(orderId)).as("하류(order-service)에 가는 사실 — fulfillment.planned 한 건")
                .singleElement().satisfies(payload -> {
                    assertThat(payload.get("outcome")).isEqualTo("UNSERVICEABLE");
                    assertThat(payload.get("reason")).isEqualTo("STALE_PLACED");
                    assertThat(payload.get("promise_revised")).as("배차되지 않은 주문에는 개정할 약속이 없다").isNull();
                });
        assertThat(processed(eventId)).as("재처리도 멱등 게이트를 지난다").isOne();
    }

    @Test
    void 전제_같은_모양의_재처리가_컷오프가_지나지_않았으면_편입된다() {
        // 위 테스트의 STALE_PLACED 가 「재처리 레코드라서」나 「예시가 틀려서」가 아니라 컷오프의 나이 때문이라는 것을 이것이 말한다.
        UUID orderId = Ids.newId();

        replay(orderId, Ids.newId(), clock.instant().plus(Duration.ofHours(2)), group);

        Map<String, Object> row = awaitOrder(orderId);
        assertThat(row.get("status")).isEqualTo("PLANNED");
        assertThat(row.get("wave_id")).isNotNull();
    }

    @Test
    void 다른_그룹을_지목한_재처리는_이_서비스가_건너뛴다() {
        UUID skipped = Ids.newId();
        UUID skippedEvent = Ids.newId();
        UUID after = Ids.newId();

        replay(skipped, skippedEvent, clock.instant().minus(Duration.ofHours(25)), "dispatch-service");
        // 같은 키(= 같은 파티션)로 뒤에 보낸 것이 처리됐다면 앞의 것은 이미 지나갔다.
        replay(after, Ids.newId(), clock.instant().minus(Duration.ofHours(25)), group);
        awaitOrder(after);

        assertThat(order(skipped)).as("건너뛴 레코드는 판정도 행도 남기지 않는다(ADR-053)").isEmpty();
        assertThat(processed(skippedEvent)).as("멱등 게이트 앞에서 건너뛴다 — processed_events 에 적지 않는다").isZero();
    }

    /**
     * {@code KafkaDeadLetters.republish} 가 만드는 모양 — 원래 키와 바이트, 원래 헤더(이벤트 타입 · 스키마 버전)에 {@code REPLAY_FOR}.
     * 레코드 시각은 비운다(지금 시각). 키는 모든 경우에 같게 둔다 — 한 파티션 안의 순서가 세 번째 테스트의 근거다.
     */
    private void replay(UUID orderId, UUID eventId, Instant cutoffAt, String targetGroup) {
        String value = example()
                .replace(EXAMPLE_EVENT_ID, eventId.toString())
                .replace(EXAMPLE_ORDER_ID, orderId.toString())
                .replace("\"cutoffAt\": \"2026-08-30T00:00:00+09:00\"", "\"cutoffAt\": \"" + cutoffAt + "\"")
                .replace("\"start\": \"2026-08-30T00:00:00+09:00\"", "\"start\": \"" + cutoffAt.plus(Duration.ofHours(1)) + "\"")
                .replace("\"end\": \"2026-08-30T07:00:00+09:00\"", "\"end\": \"" + cutoffAt.plus(Duration.ofHours(7)) + "\"")
                .replace("\"placedAt\": \"2026-08-29T13:20:11.482Z\"",
                        "\"placedAt\": \"" + cutoffAt.minus(Duration.ofHours(2)) + "\"");
        CONTRACTS.validateRecord(value);
        ProducerRecord<String, String> record = new ProducerRecord<>(ORDER_PLACED, null, null, "replay-it", value, List.of(
                new RecordHeader(EventHeaders.EVENT_TYPE, EventHeaders.toBytes("order.placed")),
                new RecordHeader(EventHeaders.SCHEMA_VERSION, EventHeaders.toBytes("1")),
                new RecordHeader(EventHeaders.REPLAY_FOR, EventHeaders.toBytes(targetGroup))));
        try {
            producer.send(record).get();
        } catch (Exception e) {
            throw new IllegalStateException("재처리 레코드를 보내지 못했다", e);
        }
    }

    private Map<String, Object> awaitOrder(UUID orderId) {
        return await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .until(() -> order(orderId), Optional::isPresent).orElseThrow();
    }

    private Optional<Map<String, Object>> order(UUID orderId) {
        return jdbc.queryForList("SELECT status, unserviceable_reason, wave_id FROM fulfillment_orders WHERE order_id = ?", orderId)
                .stream().findFirst();
    }

    private List<Map<String, Object>> planned(UUID orderId) {
        return jdbc.queryForList("""
                SELECT payload ->> 'outcome' AS outcome, payload ->> 'reason' AS reason,
                       payload ->> 'promiseRevised' AS promise_revised
                  FROM outbox_events WHERE event_type = 'fulfillment.planned' AND partition_key = ?
                """, orderId.toString());
    }

    private long processed(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Long.class, eventId);
    }

    private static String example() {
        try {
            return Files.readString(CONTRACTS.contractsDirectory().resolve("examples").resolve("order.placed.v1.example.json"),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
