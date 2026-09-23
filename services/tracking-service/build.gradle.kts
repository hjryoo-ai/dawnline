plugins {
    id("dawnline.spring-service")
}

dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:messaging"))
    implementation(project(":libs:observability"))
    // 오류 응답의 모양 — ProblemDetailsAdviceSupport (ADR-049).
    implementation(project(":libs:web"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.data.redis)
    // Boot 4 모듈화: FlywayAutoConfiguration 은 spring-boot-flyway 모듈에만 있다.
    // 이 스타터가 없으면 spring.flyway.* 가 죽은 설정이 되어 마이그레이션이 실행되지 않는다.
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.flyway.postgresql)
    // 유스케이스가 dawnline_scan_after_cancel_total 을 직접 올린다(§9.1). 지금은
    // libs/observability 가 노출하는 OTel 스타터를 타고 전이로 들어오지만, 직접 쓰는 의존은
    // 직접 선언한다 — 그 스타터를 바꾸는 날 컴파일이 깨지는 이유가 보이지 않게 된다.
    implementation(libs.micrometer.core)
    implementation(libs.springdoc.openapi.webmvc)
    runtimeOnly(libs.postgresql)

    // libs/common 의 공유 ArchUnit 규칙 (DESIGN.md §13)
    testImplementation(testFixtures(project(":libs:common")))

    // 계약 예시로 소비자 페이로드를 검증한다 (불변규칙 8) — 발행자 쪽 테스트와 같은 픽스처다.
    testImplementation(testFixtures(project(":libs:messaging")))

    // Boot 4 모듈화: @AutoConfigureMockMvc 는 spring-boot-starter-test 가 아니라 이 모듈에 있다.
    integrationTestImplementation(libs.spring.boot.webmvc.test)
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.testcontainers.redis)
}

// -----------------------------------------------------------------------------
// 계약 파일을 단위 테스트의 입력으로 선언한다 (불변규칙 8).
// RouteAssignedPayloadTest 가 contracts/events/examples 를 런타임에 읽는다. contracts/ 는 이
// 모듈의 소스도 리소스도 아니라서, 예시만 고친 빌드는 Gradle 이 보기에 "입력이 안 바뀐" 빌드가
// 되고 test 가 UP-TO-DATE 로 건너뛴다 — 검사가 돌지 않는데 초록이다 (DESIGN.md §13 규칙 3).
tasks.named<Test>("test") {
    inputs.dir(rootProject.layout.projectDirectory.dir("contracts/events"))
            .withPropertyName("eventContracts")
            .withPathSensitivity(PathSensitivity.RELATIVE)
}

// -----------------------------------------------------------------------------
// OpenAPI 문서 재생성 (DESIGN.md §5.4, §14).
//
// contracts/openapi/tracking-service.yaml 은 생성물이고, OpenApiContractIT 가 코드와 어긋나지
// 않는지 검사한다. 스캔 API 를 고치면 이 태스크로 문서를 다시 만든다 — Phase 5-2 의 sim-runner
// 가 다른 모듈에서 이 엔드포인트를 부르므로, 두 모듈이 공유하는 것은 이 문서뿐이다.
tasks.register<Test>("updateOpenApi") {
    description = "contracts/openapi/tracking-service.yaml 을 코드에서 다시 만든다"
    group = "documentation"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*OpenApiContractIT*") }
    systemProperty("dawnline.openapi.update", "true")
    outputs.upToDateWhen { false }
}
