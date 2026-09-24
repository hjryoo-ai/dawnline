import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    id("dawnline.spring-service")
    alias(libs.plugins.openapi.generator)
}

dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:messaging"))
    implementation(project(":libs:observability"))
    // 오류 응답의 모양 — 이 서비스의 첫 @RestControllerAdvice (ADR-049, ArchUnit 규칙 9)
    implementation(project(":libs:web"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.security.oauth2.resource.server)
    implementation(libs.spring.boot.starter.restclient)
    // Boot 4 모듈화: FlywayAutoConfiguration 은 spring-boot-flyway 모듈에만 있다.
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.flyway.postgresql)
    implementation(libs.springdoc.openapi.webmvc)
    runtimeOnly(libs.postgresql)

    testImplementation(testFixtures(project(":libs:common")))
    // 순서 검사의 사실 집합을 계약 예시에서 만든다 (ADR-051 결정 6, 불변규칙 8).
    testImplementation(testFixtures(project(":libs:messaging")))

    // 내부 토큰의 테스트 값(InternalTokens)과 코어의 쓰기 표면 검사(InternalTokenSurfaceContract) — ADR-055.
    integrationTestImplementation(testFixtures(project(":libs:web")))
    // OpenApiContractIT 가 MockMvc 로 /v3/api-docs 를 읽는다 (Boot 4 모듈화 — starter-test 에 없다)
    integrationTestImplementation(libs.spring.boot.webmvc.test)
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.awaitility)
}

// -----------------------------------------------------------------------------
// 코어 위임 클라이언트 — 커밋된 계약에서 빌드 때 만든다 (ADR-052 결정 1)
//
//   - 입력은 살아 있는 엔드포인트가 아니라 contracts/openapi/*.yaml 이다. 생성 태스크가 그 파일을
//     입력으로 가지므로 문서만 바뀐 빌드에서도 생성과 컴파일이 다시 돈다.
//   - 생성물은 커밋하지 않는다 — build/ 아래의 산출물이다.
//   - 생성물은 자기 소스셋(coreClients)과 자기 컴파일 단계를 가진다. 이 저장소의 린트(-Werror)는
//     우리가 쓴 코드의 규약이지 호환의 기준이 아니다(ADR-052 채택 기준 3).
// -----------------------------------------------------------------------------
val coreContracts = mapOf(
    "dispatch" to "dispatch-service.yaml",
    "order" to "order-service.yaml",
    // 작업 2 (2026-09-24): 조기 마감(ADR-054)과 outbox 격리 조회·재큐(§4.6) — tracking 은 outbox 경로만 쓴다
    "fulfillment" to "fulfillment-service.yaml",
    "tracking" to "tracking-service.yaml",
)

val generateCoreClients = coreContracts.map { (core, file) ->
    tasks.register<GenerateTask>("generate${core.replaceFirstChar(Char::uppercase)}Client") {
        group = "build"
        description = "contracts/openapi/$file 에서 $core 위임 클라이언트를 만든다 (ADR-052)"
        generatorName.set("spring")
        library.set("spring-http-interface")
        inputSpec.set(rootProject.layout.projectDirectory.file("contracts/openapi/$file").asFile.path)
        outputDir.set(layout.buildDirectory.dir("generated/core-clients/$core").get().asFile.path)
        apiPackage.set("com.dawnline.ops.adapter.out.core.$core.api")
        modelPackage.set("com.dawnline.ops.adapter.out.core.$core.model")
        cleanupOutput.set(true)
        globalProperties.set(mapOf("apis" to "", "models" to "", "supportingFiles" to "false",
                "modelDocs" to "false", "apiDocs" to "false", "modelTests" to "false", "apiTests" to "false"))
        configOptions.set(mapOf(
            "useSpringBoot4" to "true",
            "useJackson3" to "true",
            "useJspecify" to "true",
            "openApiNullable" to "false",
            "useBeanValidation" to "false",
            "generateJsonIncludeAnnotations" to "false",
            "generateJsonSetterNullsAnnotations" to "false",
            "annotationLibrary" to "none",
            "documentationProvider" to "none",
            "hideGenerationTimestamp" to "true",
            "sourceFolder" to "src/main/java",
        ))
    }
}

val coreClients: SourceSet = sourceSets.create("coreClients") {
    java.srcDirs(coreContracts.keys.map { layout.buildDirectory.dir("generated/core-clients/$it/src/main/java") })
}

dependencies {
    "coreClientsImplementation"(platform(libs.spring.boot.bom))
    "coreClientsImplementation"(libs.spring.boot.starter.web)
    // 생성물이 필수 칸에 붙이는 @NotNull — main 이 이미 가진 의존이다(새 런타임 의존이 아니다).
    "coreClientsImplementation"(libs.spring.boot.starter.validation)
    implementation(coreClients.output)
}

tasks.named<JavaCompile>("compileCoreClientsJava") {
    dependsOn(generateCoreClients)
    // 생성물에는 이 저장소의 린트를 걸지 않는다 — 경고는 세어서 ADR-052 에 적는다.
    options.compilerArgs = listOf("-parameters", "-Xlint:all")
}

// -----------------------------------------------------------------------------
// 테스트가 읽는 저장소 파일을 입력으로 선언한다 (CLAUDE.md — 아니면 문서·계약만 고친 빌드에서
// test 가 UP-TO-DATE 로 건너뛴다).
//   - contracts/events: 순서 검사의 사실 집합과 구독 토픽 대조가 계약 디렉터리에서 시작한다
//   - docs/DESIGN.md: 네 축의 선언 순서를 §5.5 「DDL 정정」 표와 대조한다
//   - contracts/openapi: 생성 클라이언트의 왕복 검사가 계약 스키마에서 표본을 만든다 (ADR-052 기준 4)
//   - tools/ops-token: 토큰 스크립트를 실행해 검증기와 맞춰 본다 (OpsTokenScriptTest)
// -----------------------------------------------------------------------------
tasks.named<Test>("test") {
    inputs.dir(rootProject.layout.projectDirectory.dir("tools/ops-token"))
            .withPropertyName("opsTokenScript")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.layout.projectDirectory.dir("contracts/openapi"))
            .withPropertyName("openApiContracts")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.layout.projectDirectory.dir("contracts/events"))
            .withPropertyName("eventContracts")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.layout.projectDirectory.file("docs/DESIGN.md"))
            .withPropertyName("design")
            .withPathSensitivity(PathSensitivity.RELATIVE)
}

// -----------------------------------------------------------------------------
// OpenAPI 문서 재생성 (DESIGN.md §5.5 · §11 — Phase 6 묶음 C).
//
// contracts/openapi/ops-api.yaml 은 생성물이고, OpenApiContractIT 가 코드와 어긋나지 않는지 검사한다.
// 소비자는 ops-web 의 TS 클라이언트다(ADR-056). 컨트롤러를 고치면 이 태스크로 문서를 다시 만든다.
tasks.register<Test>("updateOpenApi") {
    description = "contracts/openapi/ops-api.yaml 을 코드에서 다시 만든다"
    group = "documentation"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*OpenApiContractIT*") }
    systemProperty("dawnline.openapi.update", "true")
    outputs.upToDateWhen { false }
}
