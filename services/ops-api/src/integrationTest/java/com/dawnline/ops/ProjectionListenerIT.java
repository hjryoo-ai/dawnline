package com.dawnline.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.messaging.MessagingMetrics;
import com.dawnline.messaging.contract.EventContracts;
import com.dawnline.ops.adapter.in.messaging.ListenerTopics;
import com.dawnline.ops.adapter.in.messaging.ProjectionListener;
import com.dawnline.ops.adapter.in.messaging.ProjectionScenario;
import com.dawnline.ops.adapter.in.messaging.ProjectionScenario.Event;
import com.dawnline.ops.support.RowDiff;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

/**
 * 브로커를 지나는 길 — 구독·역직렬화·멱등 (DESIGN.md §5.5, 불변규칙 2).
 *
 * <p>{@code ProjectionShuffleIT} 는 순서를 <em>우리가</em> 정하려고 브로커를 건너뛴다. 이 클래스는
 * 그 반대편이다: 열한 토픽에 실제로 발행하고, 열한 리스너가 실제로 받는지 본다. 토픽이 다르면
 * 소비 순서는 Kafka 가 정하므로, 끝난 뒤의 행이 인과 순서로 직접 넣은 행과 같아야 한다 — 이것도
 * ADR-051 의 한 표본이다(씨앗이 없어 재현은 못 하지만, 실제 소비 경로의 순서다).
 */
@SpringBootTest(classes = OpsApplication.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("ProjectionListenerIT — 열한 토픽을 브로커로 받는다")
class ProjectionListenerIT extends OpsIntegrationTestBase {

    /** deploy/compose/.env.example 의 {@code KAFKA_IMAGE} 와 같은 태그. */
    private static final String KAFKA_IMAGE = "apache/kafka:4.3.1";

    /** 자동 토픽 생성을 끈다 — compose 의 브로커와 같다. 켜 두면 토픽 이름 오타가 여기서 통과한다. */
    private static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE)
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    static {
        KAFKA.start();
        // 리스너가 붙기 전에 만든다. 토픽 목록은 리스너의 어노테이션에서 읽는다 — 열거하지 않는다.
        // 파티션을 셋으로 둔다 — 한 토픽 안에서도 키가 다르면 순서가 없다는 것까지 실제로 돈다.
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()))) {
            admin.createTopics(ListenerTopics.of().keySet().stream().map(t -> new NewTopic(t, 3, (short) 1)).toList())
                    .all().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("테스트 토픽을 만들지 못했습니다", e);
        }
    }

    /** 이 클래스는 소비 경로를 본다 — 릴레이는 끈다(발행할 outbox 가 없다). */
    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("dawnline.messaging.outbox.enabled", () -> "false");
    }

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private ProjectionListener listener;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    private final ProjectionScenario scenario = new ProjectionScenario(EventContracts.load());
    private ReadModelTables tables;

    @BeforeEach
    void setUp() {
        tables = new ReadModelTables(jdbc);
        wipe();
    }

    @AfterEach
    void wipe() {
        tables.delete(scenario.keys());
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("DELETE FROM processed_events WHERE event_id = ANY (?)");
            statement.setArray(1, connection.createArrayOf("uuid", eventIds().toArray()));
            return statement;
        });
    }

    @Test
    void 열한_토픽에서_받고_끝난_행이_인과_순서로_넣은_행과_같다() {
        for (Event event : scenario.causalOrder()) {
            kafka.send(event.topic(), event.key(), event.value());
        }
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(processed()).isEqualTo(scenario.causalOrder().size()));
        var viaBroker = tables.snapshot(scenario.keys(), ProjectionShuffleIT.EXCLUDED_COLUMNS.keySet());

        wipe();
        long offset = 0;
        for (Event event : scenario.causalOrder()) {
            ListenerTopics.deliver(listener,
                    new ConsumerRecord<>(event.topic(), 0, offset++, event.key(), event.value()));
        }
        var causal = tables.snapshot(scenario.keys(), ProjectionShuffleIT.EXCLUDED_COLUMNS.keySet());

        assertThat(causal.get("rm_orders")).as("기준 행이 있다").hasSize(5);
        assertThat(RowDiff.between(causal, viaBroker, scenario::name)).isEmpty();
    }

    @Test
    void 같은_이벤트를_두_번_보내도_한_번만_반영한다() {
        Event placed = scenario.causalOrder().getFirst();
        double duplicatesBefore = duplicates();

        kafka.send(placed.topic(), placed.key(), placed.value());
        kafka.send(placed.topic(), placed.key(), placed.value());

        // 전제: 두 번째가 실제로 도착해 걸렸다. 이것 없이 「한 번만」을 보면 두 번째가 아직 안 온
        // 것과 구별되지 않는다 — 공허하게 통과한다.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(duplicates()).isEqualTo(duplicatesBefore + 1));
        assertThat(processed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT order_status FROM rm_orders WHERE order_id = ?", String.class,
                scenario.o1)).isEqualTo("PLACED");
    }

    private List<UUID> eventIds() {
        List<UUID> ids = new ArrayList<>();
        for (Event event : scenario.causalOrder()) {
            ids.add(event.eventId());
        }
        return ids;
    }

    private int processed() {
        Integer count = jdbc.query(connection -> {
            var statement = connection.prepareStatement(
                    "SELECT count(*) FROM processed_events WHERE consumer = 'ops-api' AND event_id = ANY (?)");
            statement.setArray(1, connection.createArrayOf("uuid", eventIds().toArray()));
            return statement;
        }, rs -> rs.next() ? rs.getInt(1) : -1);
        return count == null ? -1 : count;
    }

    private double duplicates() {
        Counter counter = meters.find(MessagingMetrics.EVENT_PROCESSED)
                .tag(MessagingMetrics.TAG_CONSUMER, "ops-api")
                .tag(MessagingMetrics.TAG_EVENT_TYPE, "order.placed")
                .tag(MessagingMetrics.TAG_OUTCOME, MessagingMetrics.OUTCOME_DUP)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }
}
