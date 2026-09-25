package com.dawnline.messagingtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.EventHeaders;
import com.dawnline.messaging.Topics;
import com.dawnline.messaging.outbox.OutboxAppender;
import com.dawnline.messaging.outbox.OutboxMessage;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 받는 쪽 절반 — 리스너 안에서 쓴 outbox 행은 받은 레코드의 트레이스를 잇는다 (DESIGN.md §9.2, ADR-062 결정 3).
 *
 * <p>{@link OutboxTraceparentIT} 가 「쓴 트랜잭션의 트레이스로 발행한다」를 보고, 이 검사가 「받은 레코드의 트레이스 안에서
 * 쓴다」를 본다 — 둘이 한 줄의 양쪽 끝이다. 받는 쪽을 여는 것은 Boot 가 만든 리스너 컨테이너 팩토리의 관측
 * ({@code spring.kafka.listener.observation-enabled})이다. 서비스는 팩토리를 직접 만들지 않으므로({@code
 * MessagingKafkaAutoConfiguration}) 여기의 리스너도 Boot 의 기본 팩토리 위에 선다 — 서비스와 같은 자리다.
 *
 * <p>들어오는 레코드는 이 검사가 날 프로듀서로 만든다. 그 {@code traceparent} 의 traceId 는 이 JVM 의 어떤 스팬에도 없던
 * 값이다 — 행에서 그 값이 나오면 리스너가 헤더를 부모로 이었다는 것 말고는 설명이 없다.
 */
@SpringBootTest(classes = {MessagingTestApplication.class, ListenerTraceparentIT.Relaying.class})
class ListenerTraceparentIT extends MessagingIntegrationTestBase {

    private static final String INBOUND = Topics.forEvent("delivery.status", 1);

    /**
     * 리스너가 쓰는 이벤트 — 발행되는 토픽이 있어야 릴레이가 이 DB 에서 멈추지 않는다. {@code order.cancelled} 는 안 된다:
     * {@code OutboxQuarantineIT} 가 그 토픽이 <em>없는</em> 것을 전제로 쓴다.
     */
    private static final String OUTBOUND_TYPE = "route.assigned";

    static {
        createTopic(INBOUND, 1);
        createTopic(Topics.forEvent(OUTBOUND_TYPE, 1), 1);
    }

    /**
     * 이 클래스만의 DB. 트레이싱은 서비스와 같게 켜고 OTLP 익스포터만 끈다 — {@code export.enabled=false} 는 전파기까지
     * NOOP 으로 바꾼다(ADR-062 맥락 3).
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        useIsolatedDatabase(registry, "listener_traceparent_it");
        registry.add("spring.kafka.listener.observation-enabled", () -> "true");
        registry.add("spring.kafka.template.observation-enabled", () -> "true");
        registry.add("management.tracing.sampling.probability", () -> "1.0");
        registry.add("management.tracing.propagation.produce", () -> "W3C");
        registry.add("management.tracing.export.otlp.enabled", () -> "false");
    }

    /** 받은 레코드마다 outbox 에 하나를 쓰는 리스너 — 서비스의 리스너가 하는 일의 최소형. */
    static class Relaying {

        static final BlockingQueue<UUID> WRITTEN = new LinkedBlockingQueue<>();

        private final OutboxAppender appender;
        private final TransactionTemplate transactions;

        Relaying(OutboxAppender appender, PlatformTransactionManager transactionManager) {
            this.appender = appender;
            this.transactions = new TransactionTemplate(transactionManager);
        }

        @KafkaListener(topics = "dawnline.delivery.status.v1", groupId = "listener-traceparent-it",
                properties = "auto.offset.reset=earliest")
        void on(ConsumerRecord<String, String> record) {
            UUID orderId = UUID.fromString(record.key());
            WRITTEN.add(transactions.execute(status -> appender.append(OutboxMessage.keyedByAggregate(
                    "Order", orderId, OUTBOUND_TYPE, 1, Map.of("orderId", orderId.toString())))));
        }
    }

    @Autowired
    private Tracer tracer;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 리스너_안에서_쓴_행은_받은_레코드의_트레이스를_잇는다() throws Exception {
        // 전제 — 트레이서가 진짜다. NOOP 이면 행의 traceparent 는 어느 쪽이든 비어 있어 아래 비교가 아무것도 보지 않는다.
        assertThat(tracer).as("트레이서가 NOOP 이 아니다").isNotSameAs(Tracer.NOOP);
        assertThat(INBOUND).as("리스너의 토픽 리터럴이 계약 이름과 같다").isEqualTo("dawnline.delivery.status.v1");

        String inboundTraceId = hex(16);
        String traceparent = "00-" + inboundTraceId + "-" + hex(8) + "-01";
        UUID orderId = UUID.randomUUID();
        send(orderId, traceparent);

        UUID eventId = Relaying.WRITTEN.poll(30, TimeUnit.SECONDS);
        assertThat(eventId).as("리스너가 레코드를 받아 outbox 에 썼다").isNotNull();

        // 리스너는 커밋한 뒤에 id 를 넘긴다(execute 가 돌아온 뒤) — 행은 이미 있다.
        String stored = jdbc.queryForObject(
                "SELECT headers ->> 'traceparent' FROM outbox_events WHERE id = ?", String.class, eventId);
        // 리스너 관측이 꺼져 있으면 소비 스팬이 없다 — 행에는 traceparent 가 실리지 않는다(음성 표본으로 확인).
        assertThat(stored).as("리스너 안에서 쓴 행의 traceparent").isNotNull();
        assertThat(EventHeaders.traceIdFrom(stored)).as("받은 레코드의 traceId 를 잇는다").contains(inboundTraceId);
    }

    private static void send(UUID orderId, String traceparent) throws Exception {
        Map<String, Object> config = Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        try (KafkaProducer<String, String> producer =
                new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
            ProducerRecord<String, String> record = new ProducerRecord<>(INBOUND, orderId.toString(), "{}");
            record.headers().add(EventHeaders.TRACEPARENT, traceparent.getBytes(StandardCharsets.UTF_8));
            producer.send(record).get();
        }
    }

    private static String hex(int bytes) {
        byte[] random = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(random);
        return HexFormat.of().formatHex(random);
    }
}
