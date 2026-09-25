plugins {
    id("dawnline.spring-service")
}

dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:messaging"))
    implementation(project(":libs:observability"))
    // 오류 응답의 모양 — ProblemDetailsAdviceSupport (ADR-049). 첫 REST 표면(ADR-054)과 함께 왔다.
    implementation(project(":libs:web"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.data.redis)
    // Boot 4 모듈화: FlywayAutoConfiguration 은 spring-boot-flyway 모듈에만 있다.
    // 이 스타터가 없으면 spring.flyway.* 가 죽은 설정이 되어 마이그레이션이 실행되지 않는다.
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.flyway.postgresql)
    implementation(libs.springdoc.openapi.webmvc)
    runtimeOnly(libs.postgresql)

    // libs/common 의 공유 ArchUnit 규칙 (DESIGN.md §13)
    testImplementation(testFixtures(project(":libs:common")))

    // libs/messaging 의 이벤트 계약 검증 픽스처 (불변규칙 8). 발행이 브로커까지 가서 봉투까지
    // 계약을 지키는지는 FulfillmentPublishIT 가 이것으로 본다.
    testImplementation(testFixtures(project(":libs:messaging")))
    // Boot 4 모듈화: @WebMvcTest·@AutoConfigureMockMvc 는 이 모듈에 있다(dispatch 와 같은 이유 — MockMvc
    // 슬라이스는 Docker 없이 도는 단위 소스셋이고, JaCoCo 게이트는 test 소스셋만 본다).
    testImplementation(libs.spring.boot.webmvc.test)

    // 내부 토큰의 테스트 값(InternalTokens)과 코어의 쓰기 표면 검사(InternalTokenSurfaceContract) — ADR-055.
    integrationTestImplementation(testFixtures(project(":libs:web")))
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.testcontainers.redis)
}

// -----------------------------------------------------------------------------
// 계약 파일을 통합 테스트의 입력으로 선언한다 (불변규칙 8).
// contracts/ 는 이 모듈의 소스도 리소스도 아니라서, 스키마만 고친 빌드는 Gradle 이 보기에
// "입력이 안 바뀐" 빌드가 되고 FulfillmentPublishIT 가 UP-TO-DATE 로 건너뛴다.
tasks.named<Test>("integrationTest") {
    inputs.dir(rootProject.layout.projectDirectory.dir("contracts/events"))
            .withPropertyName("eventContracts")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    // OpenApiContractIT 가 읽는 contracts/openapi/fulfillment-service.yaml 은 dawnline.spring-service 규약이
    // 입력으로 건다 — 서비스마다 적으면 다음 서비스가 빠진다.
}

// -----------------------------------------------------------------------------
// OpenAPI 문서 재생성 (DESIGN.md §5.2 · §11, ADR-054).
//
// contracts/openapi/fulfillment-service.yaml 은 생성물이고, OpenApiContractIT 가 코드와 어긋나지
// 않는지 검사한다. 컨트롤러를 고치면 이 태스크로 문서를 다시 만든다. 소비자는 ops-api 의 위임
// 클라이언트다(ADR-052) — 문서가 바뀌면 그쪽 컴파일이 깨진다.
tasks.register<Test>("updateOpenApi") {
    description = "contracts/openapi/fulfillment-service.yaml 을 코드에서 다시 만든다"
    group = "documentation"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*OpenApiContractIT*") }
    systemProperty("dawnline.openapi.update", "true")
    outputs.upToDateWhen { false }
}

// -----------------------------------------------------------------------------
// RetentionTableDefaultsTest 가 docs/DESIGN.md §7.1 보존 표를 읽는다(ADR-058 결정 7) — 입력으로 선언하지 않으면
// 표만 고친 빌드에서 test 가 UP-TO-DATE 로 건너뛴다. 검사가 돌지 않는데 초록인 것은 이 검사가 막으려는 모양이다.
// 같은 이유로 단위 테스트가 읽는 파일 둘을 더 건다 — ServiceTierContractTest · UnserviceableReasonContractTest 가
// contracts/events 를, PlanOrderServiceTest 가 데모의 밀림 상한을 읽는다(ADR-063). 앞의 둘은 2026-09-25 까지 입력이
// 아니었다 — 스키마만 고친 빌드에서 ServiceTierContractTest 가 건너뛰어졌다.
// -----------------------------------------------------------------------------
tasks.named<Test>("test") {
    inputs.file(rootProject.layout.projectDirectory.file("docs/DESIGN.md"))
            .withPropertyName("retentionTable")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.layout.projectDirectory.dir("contracts/events"))
            .withPropertyName("eventContracts")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.layout.projectDirectory.file("tools/demo/phase2-demo.sh"))
            .withPropertyName("demoWavePushes")
            .withPathSensitivity(PathSensitivity.RELATIVE)
}
