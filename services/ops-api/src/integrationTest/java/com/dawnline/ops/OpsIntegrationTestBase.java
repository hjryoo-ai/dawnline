package com.dawnline.ops;

import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ops-api 통합 테스트 공통 기반 — PostgreSQL 18 (DESIGN.md §13).
 *
 * <p>컨테이너는 정적 초기화 블록에서 시작하고 Flyway 를 스프링 컨텍스트보다 <em>먼저</em> 돌린다.
 * {@code classpath:db/migration} 하나로 이 서비스의 {@code V1__ops.sql} 과 {@code libs/messaging}
 * 의 {@code V000_x} 가 함께 잡힌다(application.yml 의 주석) — 여기서 그 설정이 실제로 맞는지 확인된다.
 *
 * <p>Kafka 는 띄우지 않는다. 브로커를 실제로 쓰는 IT 가 자기 자리에서 띄운다 — 안 쓰는 컨테이너를
 * 기반에 두면 살아 있는 의존성을 전제로 쓴 검사가 조용히 섞인다(tracking 의 기반과 같은 이유).
 *
 * <p><strong>릴레이·리스너 기동에 대해 기반은 의견을 갖지 않는다</strong>(CLAUDE.md). 공유 자원을
 * 쓰는 IT 가 자기 자리에서 켜고 끈다.
 */
public abstract class OpsIntegrationTestBase {

    /** deploy/compose/.env.example 의 {@code POSTGRES_IMAGE} 와 같은 태그. */
    static final String POSTGRES_IMAGE = "postgres:18.2";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("dawnline_ops")
            .withUsername("dawnline_ops")
            .withPassword("dawnline");

    static {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /** 하위 클래스가 상속한다. */
    protected OpsIntegrationTestBase() {
    }

    /**
     * 컨테이너 주소를 컨텍스트에 넣는다. {@code spring.flyway.enabled=false} — 위 정적 블록이 이미
     * 마이그레이션을 끝냈다.
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
    }
}
