plugins {
    id("dawnline.java-conventions")
    `java-test-fixtures`
}

dependencies {
    api(project(":libs:common"))

    // Boot 4 모듈화 주의: Kafka 자동설정(KafkaAutoConfiguration)은 spring-boot-autoconfigure 가 아니라
    // spring-boot-kafka 에 있다. 이 스타터를 빼면 spring.kafka.* 가 조용히 무시되고
    // KafkaTemplate/ConsumerFactory 빈이 아예 생기지 않는다.
    api(libs.spring.boot.starter.kafka)

    // Boot 4 의 기본 Jackson 은 3.x (tools.jackson.*). 어떤 스타터도 전이로 넣어 주지 않는다.
    api(libs.jackson.databind)

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.micrometer.core)
    // 미터는 카탈로그 항목으로, 헬퍼 한 곳에서만 등록한다(ADR-060, ArchUnit 규칙 11).
    implementation(project(":libs:observability"))
    compileOnly(libs.spring.boot.starter.actuator)
    // outbox 의 traceparent 제공자(§9.2). compileOnly 인 이유: 트레이싱 스택은 서비스 규약(dawnline.spring-service)이 싣고,
    // 도구는 싣지 않는다. 그 타입을 참조하는 코드는 com.dawnline.messaging.tracing 하나에 있고 자동 설정이 클래스 조건으로
    // 가른다 — 클래스가 없으면 그 패키지는 로드되지 않는다.
    compileOnly(libs.micrometer.tracing)

    // outbox 격리 조회·재큐 엔드포인트 (DESIGN.md §4.6, ADR-015 후속 정정). compileOnly 인 이유: 웹이 없는
    // 소비자(도구·배치)가 이 라이브러리를 쓰면서 서블릿 스택을 끌어오지 않게 한다. 자동 설정은
    // DispatcherServlet 을 이름으로 조건에 걸어 그 클래스패스에서 조용히 빠진다. springdoc 은 문서 어노테이션만
    // 쓴다(libs/web 과 같은 형태) — 없는 런타임에서 어노테이션은 무시된다.
    compileOnly(libs.spring.boot.starter.web)
    compileOnly(libs.springdoc.openapi.webmvc)

    // 이 라이브러리는 Redis 를 참조하지 않는다. 릴레이 리더 락이 잠깐 Redis 를 썼고
    // (ADR-027 원 결정) 그 의존은 compileOnly 였는데, advisory lock 으로 옮기면서
    // 조정자가 이미 쓰고 있는 DataSource 가 되어 의존 자체가 사라졌다.

    // 이벤트 계약 검증 픽스처 — 서비스들의 계약 테스트가 재사용한다 (CLAUDE.md 불변규칙 8).
    testFixturesApi(libs.json.schema.validator)
    testFixturesApi(libs.jackson.databind)

    testImplementation(libs.spring.boot.starter.test)
    // 소비 측 경계표의 redis 행(ADR-015 후속 정정) — 본문은 Redis 를 참조하지 않고 패키지로 판정한다. 테스트는 실제 예외
    // (RedisConnectionFailureException · Lettuce 의 시간 초과)로 그 판정을 본다.
    testImplementation(libs.spring.boot.starter.data.redis)
    // 보존 표 파서(RetentionTable) — 모듈마다 같은 방식으로 §7.1 을 읽어야 대조가 같은 표를 본다(ADR-058).
    testImplementation(testFixtures(project(":libs:common")))
    // 컨트롤러 테스트 — 실제 WebMvc 자동 설정 위에서 서비스와 같은 어드바이스(libs/web)로 돈다.
    // integrationTest 도 이 둘을 물려받아 MessagingTestApplication 이 서블릿 웹 앱이 된다(OutboxQuarantineIT 가
    // 재큐를 HTTP 로 부른다).
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.webmvc.test)
    testImplementation(project(":libs:web"))
    // 컨트롤러의 문서 어노테이션을 읽을 수 있어야 테스트 컴파일이 -Werror 를 넘는다(libs/web 과 같은 이유).
    testImplementation(libs.springdoc.openapi.webmvc)

    // 내부 토큰의 테스트 값(InternalTokens)과 코어의 쓰기 표면 검사(InternalTokenSurfaceContract) — ADR-055.
    integrationTestImplementation(testFixtures(project(":libs:web")))
    integrationTestImplementation(libs.spring.boot.starter.test)
    integrationTestImplementation(libs.spring.boot.testcontainers)
    integrationTestImplementation(libs.testcontainers.junit.jupiter)
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.spring.boot.starter.flyway)
    integrationTestImplementation(libs.flyway.postgresql)
    integrationTestImplementation(libs.awaitility)
    // OutboxTraceparentIT — 서비스와 같은 트레이싱 스택(Micrometer Tracing → OTel)이 있어야 릴레이가 자기 폴링의 트레이스로
    // 헤더를 덮는지가 드러난다. 서비스는 dawnline.spring-service 규약이 같은 스타터를 건다(§9.2).
    integrationTestImplementation(libs.spring.boot.starter.opentelemetry)
    // 런타임이 아니라 컴파일 의존이다 — OutboxLeaderLockIT 가 PGSimpleDataSource 를 직접 만든다.
    // advisory lock 은 세션에 걸리므로 인스턴스마다 다른 커넥션이어야 하고, 그것을 스프링 없이
    // 만들려면 드라이버의 DataSource 가 필요하다.
    integrationTestImplementation(libs.postgresql)
}

// -----------------------------------------------------------------------------
// 계약 파일을 test 태스크의 입력으로 선언한다 (CLAUDE.md 불변규칙 8).
//
// EventContractsTest 는 contracts/events/ 를 런타임에 읽는다. 그런데 그 디렉터리는 이 모듈의
// 소스도 리소스도 아니라서, Gradle 이 보기에는 스키마나 예시만 고친 빌드는 "입력이 안 바뀐" 빌드다.
// 그러면 test 가 UP-TO-DATE 로 건너뛰고, 깨진 계약이 로컬에서 초록으로 보인다.
// (CI 는 매번 새 체크아웃이라 걸리지만, 그때는 이미 커밋된 뒤다.)
tasks.named<Test>("test") {
    inputs.dir(rootProject.layout.projectDirectory.dir("contracts/events"))
            .withPropertyName("eventContracts")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    // ComposeTopicsTest 가 kafka-init 의 토픽 목록을 계약과 대조한다 — 같은 이유로 입력이다.
    inputs.file(rootProject.layout.projectDirectory.file("deploy/compose/docker-compose.yml"))
            .withPropertyName("composeTopics")
            .withPathSensitivity(PathSensitivity.RELATIVE)
}

// -----------------------------------------------------------------------------
// RetentionTableDefaultsTest 가 docs/DESIGN.md §7.1 보존 표를 읽는다(ADR-058 결정 7) — 입력으로 선언하지 않으면
// 표만 고친 빌드에서 test 가 UP-TO-DATE 로 건너뛴다. 검사가 돌지 않는데 초록인 것은 이 검사가 막으려는 모양이다.
// -----------------------------------------------------------------------------
tasks.named<Test>("test") {
    inputs.file(rootProject.layout.projectDirectory.file("docs/DESIGN.md"))
            .withPropertyName("retentionTable")
            .withPathSensitivity(PathSensitivity.RELATIVE)
}

// -----------------------------------------------------------------------------
// ConsumeFailureTableTest 가 ADR-015 의 소비 측 경계표를 읽는다(후속 정정 2026-09-25) — 같은 이유로 입력이다.
// -----------------------------------------------------------------------------
tasks.named<Test>("test") {
    inputs.file(rootProject.layout.projectDirectory.file("docs/adr/ADR-015-outbox-publish-side-quarantine.md"))
            .withPropertyName("consumeFailureTable")
            .withPathSensitivity(PathSensitivity.RELATIVE)
}
