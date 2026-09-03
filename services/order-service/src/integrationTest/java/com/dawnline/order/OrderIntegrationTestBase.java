package com.dawnline.order;

import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * order-service 통합 테스트 공통 기반 — PostgreSQL 18 + Kafka 4.3 (DESIGN.md §13).
 *
 * <p>컨테이너는 정적 초기화 블록에서 시작한다. Spring 컨텍스트가 만들어지기 <em>전에</em> Flyway 를
 * 돌려야 하기 때문이다 — {@code ddl-auto=validate} 가 기동 시점에 스키마를 검증한다.
 *
 * <p>Flyway 는 {@code classpath:db/migration} <strong>하나만</strong> 준다. 이 서비스의
 * {@code V1__orders.sql} 과 {@code libs/messaging} 이 jar 로 주는 {@code V000_x} 공통 스크립트가
 * 그 한 위치로 함께 잡힌다 — 부모·자식 위치를 같이 적으면 flyway-core 가 같은 스크립트를 두 번
 * 수집해 기동이 깨진다(application.yml 의 주석 참고). 즉 여기서 그 설정이 실제로 맞는지 확인된다.
 *
 * <p>이미지 태그는 {@code deploy/compose/.env.example} 과 같은 값을 쓴다. 컨테이너에서는 되는데
 * Compose 에서는 안 되는(또는 그 반대) 상황을 없애려는 것이다.
 */
public abstract class OrderIntegrationTestBase {

    /** deploy/compose/.env.example 의 {@code POSTGRES_IMAGE} 와 같은 태그. */
    static final String POSTGRES_IMAGE = "postgres:18.2";

    /** deploy/compose/.env.example 의 {@code KAFKA_IMAGE} 와 같은 태그. */
    static final String KAFKA_IMAGE = "apache/kafka:4.3.1";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("dawnline_order")
            .withUsername("dawnline_order")
            .withPassword("dawnline");

    /**
     * 자동 토픽 생성을 끈다 — {@code deploy/compose} 의 브로커와 같은 설정이다. 켜 두면 토픽 이름
     * 오타나 누락이 테스트에서는 조용히 통과하고 운영에서만 터진다.
     */
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
    protected OrderIntegrationTestBase() {
    }

    /**
     * 컨테이너 주소를 컨텍스트에 넣는다.
     *
     * <p>{@code spring.flyway.enabled=false} 로 둔다. 위 정적 블록이 이미 마이그레이션을 끝냈고,
     * 애플리케이션이 다시 돌려도 하는 일이 없다. 끄면 "Flyway 가 스키마를 만든다" 와 "Hibernate 가
     * 그 스키마를 검증한다" 가 분리돼, validate 실패가 곧 매핑 오류라는 것이 분명해진다.
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.flyway.enabled", () -> "false");
    }

    /** 테스트 브로커 주소. */
    protected static String bootstrapServers() {
        return KAFKA.getBootstrapServers();
    }
}
