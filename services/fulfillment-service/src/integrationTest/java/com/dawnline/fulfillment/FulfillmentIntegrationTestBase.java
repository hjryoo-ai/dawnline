package com.dawnline.fulfillment;

import com.redis.testcontainers.RedisContainer;
import org.flywaydb.core.Flyway;
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
 * <p>Kafka 는 아직 띄우지 않는다. 이 단계에는 리스너도 발행도 없고, 릴레이는 브로커가 없으면
 * 폴링이 실패할 뿐 기동을 막지 않는다(§8.2 의 완충 설계, ADR-016 레디니스에서 Kafka 제외).
 * 리스너가 생기는 Phase 2-5 에서 이 기반에 브로커를 더한다.
 */
public abstract class FulfillmentIntegrationTestBase {

    /** deploy/compose/.env.example 의 {@code POSTGRES_IMAGE} 와 같은 태그. */
    static final String POSTGRES_IMAGE = "postgres:18.2";

    /** deploy/compose/.env.example 의 {@code REDIS_IMAGE} 와 같은 태그. */
    static final String REDIS_IMAGE = "redis:8.8.2";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("dawnline_fulfillment")
            .withUsername("dawnline_fulfillment")
            .withPassword("dawnline");

    private static final RedisContainer REDIS = new RedisContainer(REDIS_IMAGE);

    static {
        POSTGRES.start();
        REDIS.start();
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
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("dawnline.fulfillment.retention.cleanup-initial-delay-ms", () -> "3600000");
        // GEO 적재는 테스트가 직접 부른다. 스케줄러가 끼어들면 "적재 전" 상태를 볼 수 없다.
        registry.add("dawnline.fulfillment.geo.initial-delay-ms", () -> "3600000");
    }

    /** 테스트가 직접 psql 스타일 SQL 을 돌릴 때 쓰는 접속 정보. */
    protected static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
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
