package com.dawnline.messagingtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dawnline.messaging.Topics;
import com.dawnline.messaging.kafka.ConsumerRetryObserver;
import com.dawnline.observability.DawnlineMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * 일시적 실패는 끝없이 재시도하고 풀리면 처리된다 — 결정적 실패만 DLQ 로 간다 (DESIGN.md §4.6 「경계」, ADR-015 후속 정정).
 *
 * <p>실제 브로커와 Boot 의 기본 리스너 컨테이너 팩토리 위에서 본다 — 서비스가 서는 자리와 같다. 에러 핸들러 · 관찰자 · 인터셉터 ·
 * 리밸런스 리스너가 전부 자동 설정에서 온다.
 *
 * <p><strong>컨슈머는 그룹에 남는다.</strong> 끝없는 재시도의 한 바퀴(폴 → 리스너 실패 → 백오프)가 {@code max.poll.interval.ms}
 * 안에 들어야 한다. 이 IT 는 둘을 같은 비율로 줄여(백오프 상한 1초, 폴 간격 상한 3초) 그 간격의 몇 배를 재시도한 뒤에도 그룹의
 * 멤버가 바뀌지 않았는지 본다. 백오프 상한을 폴 간격보다 크게 두면 멤버가 바뀐다(음성 표본으로 확인했다).
 */
@SpringBootTest(classes = {MessagingTestApplication.class, ConsumerRetryIT.Failing.class})
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ConsumerRetryIT extends MessagingIntegrationTestBase {

    /**
     * 이 IT 만의 토픽. {@code wave.closed} 를 쓰면 안 된다 — {@code OutboxTraceparentIT} 가 그 토픽을 처음부터 읽어 봉투로 연다
     * (같은 컨테이너, 2026-09-25 에 여기서 넣은 {@code "{}"} 가 그 IT 를 깼다).
     */
    private static final String TOPIC = Topics.forEvent("plan.completed", 1);
    private static final String GROUP = "consumer-retry-it";
    private static final Duration MAX_POLL_INTERVAL = Duration.ofSeconds(3);

    static {
        createTopic(TOPIC, 1);
        createTopic(Topics.dlqFor(TOPIC), 1);
    }

    /**
     * 이 클래스만의 DB, 그리고 줄인 백오프 상한. 수열의 모양(200ms 에서 시작해 상한에서 멈춘다)은 운영과 같다.
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        useIsolatedDatabase(registry, "consumer_retry_it");
        registry.add("dawnline.messaging.retry.max-interval", () -> "1s");
    }

    /** 키마다 실패를 거는 리스너 — 걸려 있는 동안 던지고, 풀리면 처리한다. */
    static class Failing {

        static final Map<String, Supplier<RuntimeException>> FAILURES = new ConcurrentHashMap<>();
        static final Map<String, AtomicInteger> DELIVERIES = new ConcurrentHashMap<>();
        static final List<String> PROCESSED = new CopyOnWriteArrayList<>();

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @KafkaListener(topics = "dawnline.plan.completed.v1", groupId = GROUP,
                properties = {"auto.offset.reset=earliest", "max.poll.interval.ms=3000"})
        void on(ConsumerRecord<String, String> record) {
            DELIVERIES.computeIfAbsent(record.key(), key -> new AtomicInteger()).incrementAndGet();
            Supplier<RuntimeException> failure = FAILURES.get(record.key());
            if (failure != null) {
                throw failure.get();
            }
            PROCESSED.add(record.key());
        }
    }

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private ConsumerRetryObserver observer;

    @Test
    void 일시적_실패는_DLQ_로_가지_않고_그룹에_남은_채_재시도하다가_풀리면_처리된다() throws Exception {
        String key = "transient-" + UUID.randomUUID();
        Failing.FAILURES.put(key, () -> new CannotCreateTransactionException("Could not open JPA EntityManager",
                new SQLTransientConnectionException("Connection is not available, request timed out after 30001ms.")));
        long dlqBefore = dlqEndOffset();

        send(key);

        // 결정적 실패의 한도(첫 배달 + 3회)를 넘어선다 — 예전 핸들러라면 여기서 DLQ 로 갔다.
        await().atMost(Duration.ofSeconds(30)).until(() -> deliveries(key) > 6);
        String member = soleMember();
        assertThat(observer.ageSeconds()).as("재시도 나이가 오른다").isPositive();

        // 폴 간격 상한의 세 배 넘게 더 재시도한다 — 컨슈머가 그룹에서 쫓겨났다면 멤버가 바뀐다.
        int deliveriesBefore = deliveries(key);
        Thread.sleep(MAX_POLL_INTERVAL.multipliedBy(3).plusSeconds(1).toMillis());
        assertThat(deliveries(key)).as("그동안에도 재시도했다").isGreaterThan(deliveriesBefore);
        assertThat(soleMember()).as("같은 멤버 — max.poll.interval 을 넘기지 않았다").isEqualTo(member);
        assertThat(dlqEndOffset()).as("DLQ 로 간 것이 없다").isEqualTo(dlqBefore);
        assertThat(retries("db_connection")).as("재시도 카운터 — 사유는 경계표의 행").isGreaterThanOrEqualTo(deliveries(key) - 1);

        Failing.FAILURES.remove(key);

        await().atMost(Duration.ofSeconds(10)).until(() -> Failing.PROCESSED.contains(key));
        await().atMost(Duration.ofSeconds(5)).until(() -> observer.ageSeconds() == 0.0);
        assertThat(dlqEndOffset()).as("풀린 뒤에도 DLQ 는 그대로다").isEqualTo(dlqBefore);
    }

    @Test
    void 결정적_실패는_첫_배달과_재시도_셋_뒤에_DLQ_로_간다() throws Exception {
        String key = "deterministic-" + UUID.randomUUID();
        Failing.FAILURES.put(key, () -> new IllegalArgumentException("알 수 없는 티어: FOO"));
        long dlqBefore = dlqEndOffset();
        double argumentBefore = retries("argument");

        send(key);

        await().atMost(Duration.ofSeconds(30)).until(() -> dlqEndOffset() == dlqBefore + 1);
        assertThat(deliveries(key)).as("첫 배달 + 재시도 셋").isEqualTo(4);
        assertThat(retries("argument") - argumentBefore).as("재시도 경로에 든 실패한 배달").isEqualTo(4.0);
        await().atMost(Duration.ofSeconds(5)).until(() -> observer.ageSeconds() == 0.0);
        assertThat(Failing.PROCESSED).doesNotContain(key);
    }

    private static int deliveries(String key) {
        AtomicInteger count = Failing.DELIVERIES.get(key);
        return count == null ? 0 : count.get();
    }

    private double retries(String reason) {
        return meters.get(DawnlineMetrics.EVENT_RETRY.meterName()).tag("reason", reason).counter().count();
    }

    private static void send(String key) throws Exception {
        Map<String, Object> config = Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        try (KafkaProducer<String, String> producer =
                new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(TOPIC, key, "{}")).get();
        }
    }

    /** DLQ 의 끝 오프셋 — 읽어서 세지 않는다. 끝 오프셋은 한도가 없는 셈이다(CLAUDE.md 「상한이 있는 조회로 전체를 결론 내지 않는다」). */
    private static long dlqEndOffset() throws Exception {
        TopicPartition partition = new TopicPartition(Topics.dlqFor(TOPIC), 0);
        try (Admin admin = admin()) {
            return admin.listOffsets(Map.of(partition, OffsetSpec.latest())).partitionResult(partition).get().offset();
        }
    }

    /** 그룹의 유일한 멤버 id — 쫓겨났다가 다시 들어오면 바뀐다. */
    private static String soleMember() throws Exception {
        try (Admin admin = admin()) {
            ConsumerGroupDescription group = admin.describeConsumerGroups(Set.of(GROUP)).describedGroups()
                    .get(GROUP).get();
            assertThat(group.members()).as("그룹 %s 의 멤버", GROUP).hasSize(1);
            MemberDescription member = group.members().iterator().next();
            return member.consumerId();
        }
    }

    private static Admin admin() {
        Properties config = new Properties();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        return Admin.create(config);
    }
}
