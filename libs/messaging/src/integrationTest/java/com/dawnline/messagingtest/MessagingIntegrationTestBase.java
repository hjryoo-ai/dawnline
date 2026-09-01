package com.dawnline.messagingtest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 통합 테스트 공통 기반 — PostgreSQL 18 + Kafka 4.3 컨테이너 (DESIGN.md §13).
 *
 * <p>컨테이너는 정적 초기화 블록에서 시작한다. Spring 컨텍스트가 만들어지기 <em>전에</em>
 * Flyway 를 돌려 스키마를 만들어야 하기 때문이다({@code ddl-auto=validate} 가 기동 시 검증한다).
 * {@code @Testcontainers} 확장을 쓰면 시작 시점이 JUnit 확장 순서에 묶여 이 순서를 보장하기 어렵다.
 *
 * <p>Flyway 는 {@code classpath:db/migration/common} 만 적용한다. 이것이 서비스가
 * {@code spring.flyway.locations} 에 추가해야 하는 바로 그 위치이고, 여기서 그 위치가 실제로
 * 두 테이블을 만드는지 확인된다.
 *
 * <p>이미지 태그는 {@code deploy/compose/.env.example} 과 같은 값을 쓴다. 컨테이너에서 되는데
 * Compose 에서 안 되는(또는 그 반대) 상황을 없애려는 것이다.
 */
public abstract class MessagingIntegrationTestBase {

    /** deploy/compose/.env.example 의 POSTGRES_IMAGE 와 같은 태그. */
    private static final String POSTGRES_IMAGE = "postgres:18.2";

    /** deploy/compose/.env.example 의 KAFKA_IMAGE 와 같은 태그. */
    private static final String KAFKA_IMAGE = "apache/kafka:4.3.1";

    /** 공통 Flyway 스크립트 위치. 서비스도 이 값을 spring.flyway.locations 에 넣어야 한다. */
    private static final String COMMON_MIGRATIONS = "classpath:db/migration/common";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("dawnline_messaging")
            .withUsername("dawnline")
            .withPassword("dawnline");

    private static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE);

    static {
        POSTGRES.start();
        KAFKA.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(COMMON_MIGRATIONS)
                .load()
                .migrate();
    }

    /** 하위 클래스가 상속한다. */
    protected MessagingIntegrationTestBase() {
    }

    /**
     * 컨테이너 주소와 공통 설정을 컨텍스트에 넣는다.
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // Flyway 로 만든 스키마와 JPA 엔티티가 정확히 일치하는지 기동 시 검증한다 (§7.1).
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // 봉투의 producer. 없으면 자동설정이 기동 시점에 예외를 던진다.
        registry.add("spring.application.name", () -> "order-service");
        // 릴레이가 폴링·메트릭·정리 세 작업을 돌리므로 풀이 1이면 서로 밀린다.
        registry.add("spring.task.scheduling.pool.size", () -> "3");
    }

    /** 테스트 브로커 주소. */
    protected static String bootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    /**
     * 토픽을 만든다. 브로커의 자동 생성 설정에 기대지 않는다 — 운영 브로커는 자동 생성이 꺼져 있다
     * ({@code deploy/compose} 의 {@code KAFKA_AUTO_CREATE_TOPICS_ENABLE=false}).
     *
     * @param topic      토픽 이름
     * @param partitions 파티션 수
     */
    protected static void createTopic(String topic, int partitions) {
        Properties config = new Properties();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        try (Admin admin = Admin.create(config)) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("토픽 생성이 중단됐습니다: " + topic, e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TopicExistsException) {
                return;
            }
            throw new IllegalStateException("토픽을 만들 수 없습니다: " + topic, e);
        }
    }

    /**
     * 토픽의 처음부터 레코드를 {@code expected} 건 이상 읽을 때까지 폴링한다.
     *
     * <p>한 번의 {@code poll} 은 리밸런스 때문에 자주 빈 결과를 준다. 단발 poll 로 어설션하면
     * 간헐적으로 실패하는 테스트가 된다.
     *
     * @param topic    토픽 이름
     * @param groupId  컨슈머 그룹
     * @param expected 최소 기대 건수
     * @param timeout  최대 대기 시간
     * @return 읽은 레코드들 (기대 건수에 못 미치면 읽은 만큼)
     */
    protected static List<ConsumerRecord<String, String>> consumeAtLeast(String topic, String groupId, int expected,
            Duration timeout) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(Set.of(topic));
            while (collected.size() < expected && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    collected.add(record);
                }
            }
        }
        return collected;
    }
}
