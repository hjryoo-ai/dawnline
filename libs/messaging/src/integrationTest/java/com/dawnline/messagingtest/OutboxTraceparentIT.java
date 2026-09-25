package com.dawnline.messagingtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.messaging.EventHeaders;
import com.dawnline.messaging.Topics;
import com.dawnline.messaging.json.EventJson;
import com.dawnline.messaging.outbox.OutboxAppender;
import com.dawnline.messaging.outbox.OutboxMessage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * outbox 를 지나도 트레이스가 끊기지 않는다 — 릴레이는 <strong>행을 쓴 트랜잭션의</strong> 트레이스로 발행한다
 * (DESIGN.md §9.2, IMPLEMENTATION_PLAN 7-2).
 *
 * <p>outbox 는 쓰기와 발행을 두 스레드로 가른다. 쓰기는 요청(또는 소비)의 스팬 안에서 일어나고, 발행은 릴레이의
 * {@code @Scheduled} 폴링 안에서 일어난다 — 그 폴링은 스스로 관측되어 <em>자기</em> 트레이스를 갖는다. 그리고 Kafka
 * 템플릿의 관측은 헤더의 {@code traceparent} 를 지우고 현재 컨텍스트로 다시 쓴다({@code KafkaRecordSenderContext} 의
 * 세터가 {@code remove} 뒤 {@code add}). 그래서 행에 원래 값을 저장해 두는 것만으로는 충분하지 않다: 릴레이가
 * 그 값을 부모로 삼아 발행해야 다음 서비스가 같은 traceId 를 잇는다.
 *
 * <p>이 검사는 한 줄의 두 절반 중 <em>보내는 쪽</em>이다 — 받는 쪽(리스너 관측이 그 헤더를 부모로 잇는가)은 서비스의
 * 검사와 Compose 스모크가 본다.
 */
@SpringBootTest(classes = MessagingTestApplication.class)
class OutboxTraceparentIT extends MessagingIntegrationTestBase {

    /** 이 클래스만의 토픽 — {@code OutboxRelayIT} 는 {@code order.placed} 토픽의 레코드 수를 센다. */
    private static final String EVENT_TYPE = "wave.closed";

    private static final String TOPIC = Topics.forEvent(EVENT_TYPE, 1);

    static {
        createTopic(TOPIC, 3);
    }

    /**
     * 이 클래스만의 DB — 릴레이는 모든 미발행 행을 집으므로 다른 컨텍스트의 릴레이가 이 행을 가져가면 안 된다.
     * 트레이싱은 서비스와 같게 켠다(observability-defaults.yml 의 값) — 내보내기는 <strong>OTLP 익스포터만</strong> 끈다.
     * {@code management.tracing.export.enabled=false} 로 끄면 Boot 4.1 이 전파기까지 NOOP 으로 바꾼다(W3C 전파기 빈이
     * {@code @ConditionalOnEnabledTracingExport}) — 그러면 이 검사는 전파가 아니라 빈 전파기를 본다.
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        useIsolatedDatabase(registry, "outbox_traceparent_it");
        registry.add("spring.kafka.template.observation-enabled", () -> "true");
        registry.add("management.tracing.sampling.probability", () -> "1.0");
        registry.add("management.tracing.propagation.produce", () -> "W3C");
        registry.add("management.tracing.export.otlp.enabled", () -> "false");
    }

    @Autowired
    private OutboxAppender appender;

    @Autowired
    private Tracer tracer;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EventJson json;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void 릴레이는_행을_쓴_트랜잭션의_트레이스로_발행한다() {
        // 전제 — 트레이서가 진짜다. 테스트 컨텍스트가 트레이싱을 NOOP 으로 바꾸면 아래의 모든 비교가 빈 값끼리의 비교가
        // 되거나 아무것도 보지 않는다.
        assertThat(tracer).as("트레이서가 NOOP 이 아니다").isNotSameAs(Tracer.NOOP);

        UUID orderId = UUID.randomUUID();
        Span request = tracer.nextSpan().name("test.request").start();
        Tracer.SpanInScope scope = tracer.withSpan(request);
        UUID eventId;
        try {
            eventId = new TransactionTemplate(transactionManager).execute(status -> appender.append(
                    OutboxMessage.keyedByAggregate("Order", orderId, EVENT_TYPE, 1,
                            new OutboxRelayIT.OrderPlaced(orderId.toString(), "DAWN"))));
        } finally {
            scope.close();
            request.end();
        }
        String written = request.context().traceId();

        // (1) 쓰는 쪽 — 행에 그 트랜잭션의 traceparent 가 실린다. 기본값(NONE)이면 여기가 비어 있다.
        String stored = jdbc.queryForObject("SELECT headers ->> 'traceparent' FROM outbox_events WHERE id = ?",
                String.class, eventId);
        assertThat(stored).as("outbox 행의 traceparent — 제공자가 살아 있다").isNotNull();
        assertThat(EventHeaders.traceIdFrom(stored)).contains(written);

        // (2) 발행된 레코드
        ConsumerRecord<String, String> published = await().atMost(Duration.ofSeconds(30))
                .until(() -> find(eventId), record -> record != null);
        String sent = header(published, EventHeaders.TRACEPARENT);

        // 전제 — 템플릿의 관측이 켜져 있어 발행 쪽에 스팬이 하나 생겼다. 꺼져 있으면 저장된 값이 그대로 나가고, 그러면
        // 아래의 traceId 비교는 릴레이가 무엇을 하든 통과한다.
        assertThat(sent).as("발행된 traceparent").isNotNull();
        assertThat(spanId(sent)).as("발행 쪽 스팬이 있다 — 템플릿 관측이 헤더를 다시 썼다").isNotEqualTo(spanId(stored));

        // (3) 보내는 쪽의 요점 — 다시 쓴 헤더가 같은 트레이스다. 릴레이가 자기 폴링의 트레이스로 덮으면 여기서 갈린다.
        assertThat(EventHeaders.traceIdFrom(sent)).as("발행된 traceId = 행을 쓴 트랜잭션의 traceId").contains(written);
        assertThat(json.readEnvelope(published.value()).traceId()).as("봉투의 traceId").isEqualTo(written);
    }

    private ConsumerRecord<String, String> find(UUID eventId) {
        List<ConsumerRecord<String, String>> records =
                consumeAtLeast(TOPIC, "outbox-traceparent-it-" + UUID.randomUUID(), Integer.MAX_VALUE,
                        Duration.ofSeconds(5));   // 재실행한 행이 남아 있을 수 있다 — 창 동안 전부 읽고 eventId 로 고른다
        return records.stream()
                .filter(record -> json.readEnvelope(record.value()).eventId().equals(eventId))
                .findFirst()
                .orElse(null);
    }

    /** {@code 00-<trace-id>-<span-id>-<flags>} 의 span-id. */
    private static String spanId(String traceparent) {
        return traceparent.split("-")[2];
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
