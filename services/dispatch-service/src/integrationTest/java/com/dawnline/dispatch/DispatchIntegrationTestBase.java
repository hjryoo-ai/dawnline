package com.dawnline.dispatch;

import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * dispatch-service 통합 테스트 공통 기반 (DESIGN.md §13).
 *
 * <p>컨테이너는 정적 초기화 블록에서 시작하고 Flyway 를 Spring 컨텍스트보다 <em>먼저</em> 돌린다.
 * {@code ddl-auto=validate} 가 기동 시점에 스키마를 검증하므로 <strong>컨텍스트가 뜬다는 사실
 * 자체가</strong> "Flyway 스키마와 JPA 엔티티가 맞다" 의 검증이다.
 *
 * <p>릴레이·스케줄 설정에 <strong>의견을 갖지 않는다.</strong> fulfillment 기반이 그것으로
 * 데인 적이 있다 — 두 {@code @DynamicPropertySource} 의 적용 순서가 보장되지 않아 기반의 의견이
 * 하위 클래스의 의견을 이겼고, 그 IT 는 아무것도 발행되지 않은 채 브로커를 기다리다 실패했다.
 */
public abstract class DispatchIntegrationTestBase {

    /** deploy/compose/.env.example 의 {@code POSTGRES_IMAGE} 와 같은 태그. */
    static final String POSTGRES_IMAGE = "postgres:18.2";

    /** deploy/compose/.env.example 의 {@code KAFKA_IMAGE} 와 같은 태그. */
    static final String KAFKA_IMAGE = "apache/kafka:4.3.1";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("dawnline_dispatch")
            .withUsername("dawnline_dispatch")
            .withPassword("dawnline");

    private static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE)
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    static {
        POSTGRES.start();
        KAFKA.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /** 하위 클래스가 상속한다. */
    protected DispatchIntegrationTestBase() {
    }

    /**
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // 위 정적 블록이 이미 마이그레이션을 끝냈다. 끄면 validate 실패가 곧 매핑 오류가 된다.
        registry.add("spring.flyway.enabled", () -> "false");
    }

    /** 테스트가 직접 SQL 을 돌릴 때 쓰는 접속 정보. */
    protected static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    /** 테스트 브로커 주소. */
    protected static String bootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    /**
     * 토픽을 미리 만든다. 브로커가 자동 생성을 꺼 두었기 때문이다. 리스너가 붙기 <em>전에</em>
     * 만들어야 하므로 호출하는 쪽은 정적 초기화 블록에서 부른다.
     *
     * <p>이미 있는 토픽은 무시한다 — 컨테이너를 공유하는 IT 여럿이 겹치는 토픽을 필요로 하고,
     * 두 번째 호출이 터지면 <em>정적 초기화 실패</em>라 그 클래스의 모든 테스트가
     * {@code initializationError} 하나로 뭉개진다(fulfillment 에서 그랬다).
     *
     * @param names 만들 토픽 이름들
     */
    protected static void createTopics(String... names) {
        try (org.apache.kafka.clients.admin.Admin admin = org.apache.kafka.clients.admin.Admin.create(
                java.util.Map.of(org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                        KAFKA.getBootstrapServers()))) {
            admin.createTopics(java.util.Arrays.stream(names)
                    .map(name -> new org.apache.kafka.clients.admin.NewTopic(name, 1, (short) 1))
                    .toList()).values().forEach((name, future) -> {
                        try {
                            future.get();
                        } catch (Exception e) {
                            if (!(e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) {
                                throw new IllegalStateException("토픽 생성 실패: " + name, e);
                            }
                        }
                    });
        }
    }
}
