package com.dawnline.tracking;

import com.dawnline.web.internal.InternalTokens;
import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * tracking-service 통합 테스트 공통 기반 — PostgreSQL 18 (DESIGN.md §13).
 *
 * <p>컨테이너는 정적 초기화 블록에서 시작한다. Spring 컨텍스트가 만들어지기 <em>전에</em> Flyway 를
 * 돌려야 하기 때문이다 — {@code ddl-auto=validate} 가 기동 시점에 스키마를 검증하므로,
 * <strong>컨텍스트가 뜬다는 사실 자체가</strong> "Flyway 스키마와 JPA 매핑이 맞다" 의 검증이다.
 *
 * <p>Redis·Kafka 는 띄우지 않는다. 지금 이 서비스에는 리스너도 발행 경로도 없고, 필요해지는
 * 항목(5-1a 소비 · 5-1b 발행)에서 그때 붙인다. 안 쓰는 컨테이너를 미리 띄우면 테스트가 느려지는
 * 것으로 끝나지 않는다 — 살아 있는 의존성을 전제로 쓴 검사가 조용히 섞인다.
 *
 * <p><strong>릴레이·스케줄러에 대해 기반은 의견을 갖지 않는다</strong>(CLAUDE.md). 공유 자원을
 * 쓰는 IT 가 자기 자리에서 켜고 끈다 — 기반이 정한 기본값은 하위 클래스가 말하지 않는 조용한
 * 전제가 되고, {@code @DynamicPropertySource} 둘의 적용 순서는 보장되지 않아 하위 클래스가
 * 그것을 뒤집지도 못한다.
 */
public abstract class TrackingIntegrationTestBase {

    /** deploy/compose/.env.example 의 {@code POSTGRES_IMAGE} 와 같은 태그. */
    static final String POSTGRES_IMAGE = "postgres:18.2";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("dawnline_tracking")
            .withUsername("dawnline_tracking")
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
    protected TrackingIntegrationTestBase() {
    }

    /**
     * 컨테이너 주소를 컨텍스트에 넣는다.
     *
     * <p>{@code spring.flyway.enabled=false} 로 둔다. 위 정적 블록이 이미 마이그레이션을 끝냈고,
     * 끄면 "Flyway 가 스키마를 만든다" 와 "Hibernate 가 그 스키마를 검증한다" 가 분리돼 validate
     * 실패가 곧 매핑 오류라는 것이 분명해진다.
     *
     * @param registry 동적 속성 레지스트리
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        // 내부 토큰 (ADR-055) — 없으면 컨텍스트가 뜨지 않는다. 공유 자원이 아니라 기동 조건이라 기반이 넣는다.
        registry.add(InternalTokens.SECRET_PROPERTY, () -> InternalTokens.TEST_TOKEN);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
    }
}
