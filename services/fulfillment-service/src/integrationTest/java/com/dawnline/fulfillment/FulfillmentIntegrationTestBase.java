package com.dawnline.fulfillment;

import com.redis.testcontainers.RedisContainer;
import org.flywaydb.core.Flyway;
import org.testcontainers.kafka.KafkaContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;


/**
 * fulfillment-service 통합 테스트 공통 기반 — PostgreSQL 18 (DESIGN.md §13).
 *
 * <p>컨테이너는 정적 초기화 블록에서 시작한다. Spring 컨텍스트가 만들어지기 <em>전에</em> Flyway 를
 * 돌려야 하기 때문이다 — {@code ddl-auto=validate} 가 기동 시점에 스키마를 검증하므로,
 * <strong>컨텍스트가 뜬다는 사실 자체가</strong> "Flyway 스키마와 JPA 엔티티가 맞다" 의 검증이다.
 *
 * <p>Redis 도 함께 띄운다(§7.2). 다만 <strong>Redis 가 없어도 성립해야 하는 것</strong>을 보는
 * 테스트는 이 기반을 쓰되 죽은 주소로 덮어쓴다({@code GeoFallbackIT}) — 로컬에 Redis 가 떠 있는지에
 * 따라 검사 대상이 달라지는 테스트는 아무것도 증명하지 못한다.
 *
 * <p>Kafka 도 띄운다(Phase 2-5 에서 리스너와 발행이 생겼다). 자동 토픽 생성은 꺼 둔다 —
 * {@code deploy/compose} 의 브로커와 같은 설정이고, 켜 두면 토픽 이름 오타가 테스트에서는 조용히
 * 통과하고 운영에서만 터진다.
 *
 * <p>릴레이 설정은 <strong>여기서 건드리지 않는다.</strong> 처음에는 기반에서 끄고
 * {@code FulfillmentPublishIT} 가 켜게 했는데, 두 {@code @DynamicPropertySource} 의 적용 순서가
 * 보장되지 않아 기반의 "끔" 이 이겼다 — 그 IT 는 <em>아무것도 발행되지 않은 채</em> 브로커를
 * 60초 기다리다 실패했다. {@code GeoFallbackIT} 에서 겪은 것과 같은 함정이고, 그때는
 * {@code spring.data.redis.url} 이 host/port 보다 우선한다는 성질로 피했지만 여기서는 같은
 * 속성이라 그 수가 없다.
 *
 * <p>그래서 기반은 이 속성에 <em>의견을 갖지 않는다</em>. 끄고 싶은 IT 가 자기 자리에서 끈다 —
 * 하위 클래스끼리는 순서 문제가 없다.
 */
public abstract class FulfillmentIntegrationTestBase {

    /** deploy/compose/.env.example 의 {@code POSTGRES_IMAGE} 와 같은 태그. */
    static final String POSTGRES_IMAGE = "postgres:18.2";

    /** deploy/compose/.env.example 의 {@code REDIS_IMAGE} 와 같은 태그. */
    static final String REDIS_IMAGE = "redis:8.8.2";

    /** deploy/compose/.env.example 의 {@code KAFKA_IMAGE} 와 같은 태그. */
    static final String KAFKA_IMAGE = "apache/kafka:4.3.1";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("dawnline_fulfillment")
            .withUsername("dawnline_fulfillment")
            .withPassword("dawnline");

    private static final RedisContainer REDIS = new RedisContainer(REDIS_IMAGE);

    private static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE)
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    static {
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /** 하위 클래스가 상속한다. */
    protected FulfillmentIntegrationTestBase() {
    }

    /**
     * 컨테이너 주소를 컨텍스트에 넣는다.
     *
     * <p>{@code spring.flyway.enabled=false} 로 둔다. 위 정적 블록이 이미 마이그레이션을 끝냈고,
     * 끄면 "Flyway 가 스키마를 만든다" 와 "Hibernate 가 그 스키마를 검증한다" 가 분리돼 validate
     * 실패가 곧 매핑 오류라는 것이 분명해진다.
     *
     * <p>보존 정리 스케줄은 끈다. 배치가 테스트 데이터를 지우는 시점을 스케줄러가 정하면 테스트가
     * 간헐적으로 깨진다 — 정리 동작 자체는 {@code FulfillmentRetentionIT} 가 직접 호출해서 본다.
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("dawnline.fulfillment.retention.cleanup-initial-delay-ms", () -> "3600000");
        // GEO 적재는 테스트가 직접 부른다. 스케줄러가 끼어들면 "적재 전" 상태를 볼 수 없다.
        registry.add("dawnline.fulfillment.geo.initial-delay-ms", () -> "3600000");
        // 컷오프 마감도 마찬가지다. 스케줄러가 먼저 닫으면 테스트의 closeDue() 가 0 을 돌려주고,
        // 그 실패는 실행 순서에 따라 나타났다 사라진다 — 가장 나쁜 종류다.
        registry.add("dawnline.fulfillment.wave.close-initial-delay-ms", () -> "3600000");
    }

    /** 테스트가 직접 psql 스타일 SQL 을 돌릴 때 쓰는 접속 정보. */
    protected static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    /** 테스트 브로커 주소. */
    protected static String bootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    /**
     * 토픽을 미리 만든다. 브로커가 자동 생성을 꺼 두었기 때문이다.
     *
     * <p>리스너가 붙기 <em>전에</em> 만들어야 한다 — 호출하는 쪽은 정적 초기화 블록에서 부른다.
     *
     * <p><strong>이미 있는 토픽은 무시한다.</strong> 컨테이너를 공유하는 IT 여럿이 겹치는 토픽을
     * 필요로 하고, 두 번째 호출이 {@code TopicExistsException} 으로 터지면 <em>정적 초기화 실패</em>
     * 라 그 클래스의 모든 테스트가 {@code initializationError} 하나로 뭉개진다(실제로 그랬다).
     * 만드는 것이 목적이지 <em>내가</em> 만드는 것이 목적이 아니다.
     *
     * @param names 만들 토픽 이름들
     */
    protected static void createTopics(String... names) {
        try (org.apache.kafka.clients.admin.Admin admin = org.apache.kafka.clients.admin.Admin.create(
                java.util.Map.of(org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                        KAFKA.getBootstrapServers()))) {
            java.util.Map<String, org.apache.kafka.common.KafkaFuture<Void>> created =
                    admin.createTopics(java.util.Arrays.stream(names)
                                    .map(name -> new org.apache.kafka.clients.admin.NewTopic(name, 1, (short) 1))
                                    .toList())
                            .values();
            for (java.util.Map.Entry<String, org.apache.kafka.common.KafkaFuture<Void>> entry
                    : created.entrySet()) {
                awaitTopic(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void awaitTopic(String name, org.apache.kafka.common.KafkaFuture<Void> future) {
        try {
            future.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException) {
                return;
            }
            throw new IllegalStateException("테스트 토픽을 만들지 못했습니다: " + name, e);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("테스트 토픽을 만들지 못했습니다: " + name, e);
        }
    }

    /** 살아 있는 Redis 주소. 죽은 Redis 를 쓰는 테스트가 대비로 쓴다. */
    protected static String redisHost() {
        return REDIS.getHost();
    }

    /** 살아 있는 Redis 포트. */
    protected static int redisPort() {
        return REDIS.getMappedPort(6379);
    }
}
