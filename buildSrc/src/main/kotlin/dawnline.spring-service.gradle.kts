/*
 * Spring Boot 실행 가능 서비스 규약.
 *  - dawnline.java-conventions + Spring Boot 플러그인
 *  - 컨테이너 이미지는 Buildpacks(bootBuildImage)로 생성 (ADR-013)
 *  - 모든 서비스는 actuator health/readiness 를 노출한다 (DESIGN.md §8.6)
 */

plugins {
    id("dawnline.java-conventions")
    id("org.springframework.boot")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    add("implementation", libs.findLibrary("spring-boot-starter").get())
    add("implementation", libs.findLibrary("spring-boot-starter-actuator").get())
    add("implementation", libs.findLibrary("micrometer-registry-prometheus").get())
    // 트레이싱(Micrometer Tracing → OTLP, DESIGN.md §9.2). libs/observability 에서 옮겨 왔다 — 그 모듈은 이제
    // libs/messaging · libs/web 이 참조하고(ADR-060), 웹이 아닌 소비자(sim-runner)에게 OTel 스택을 끌고 가면 안 된다.
    // Boot 4 모듈화 주의: 트레이싱 자동설정은 spring-boot-micrometer-tracing-opentelemetry / spring-boot-opentelemetry 에
    // 있고, 이 스타터가 그것들과 bridge-otel · exporter-otlp 를 한 번에 가져온다. 빼면 MDC 의 traceId 도 OTLP 도 없다.
    add("implementation", libs.findLibrary("spring-boot-starter-opentelemetry").get())

    add("testImplementation", libs.findLibrary("spring-boot-starter-test").get())
    add("integrationTestImplementation", libs.findLibrary("spring-boot-starter-test").get())
    add("integrationTestImplementation", libs.findLibrary("spring-boot-testcontainers").get())
    add("integrationTestImplementation", libs.findLibrary("testcontainers-junit-jupiter").get())

    // 아키텍처 경계 테스트 (DESIGN.md §13)
    add("testImplementation", libs.findLibrary("archunit-junit5").get())
}

// -----------------------------------------------------------------------------
// 커밋된 OpenAPI 문서를 integrationTest 의 입력으로 선언한다 (DESIGN.md §11, CLAUDE.md 「서로를 비추는
// 목록에는 대조 검사를 둔다」).
//
// OpenApiContractIT 는 contracts/openapi/<서비스>.yaml 을 런타임에 읽는다. 그 파일은 이 모듈의 소스도
// 리소스도 아니라서, 선언하지 않으면 문서만 손으로 고친 빌드에서 Gradle 이 integrationTest 를 UP-TO-DATE
// 로 건너뛴다 — 「문서는 생성물이다」를 지키는 검사가 돌지 않은 채 초록이 된다(2026-09-24 에 tracking 으로
// 재현했다: 문서 끝에 한 줄을 붙이고 돌렸더니 UP-TO-DATE).
//
// **서비스마다가 아니라 여기에 둔다.** 처음에는 fulfillment 하나에만 있었고 dispatch·order·tracking 에는
// 없었다. 서비스마다 적는 선언은 문서가 늘 때마다 기억해야 하는 규칙이고, 그런 규칙은 조용히 샌다 —
// 다음 문서(ops-api 의 것)가 정확히 그 자리다.
//
// inputs.file 이 아니라 inputs.files 인 이유: 문서가 없는 서비스(ops-api)도 이 규약을 쓴다. inputs.file 은 파일이
// 없으면 태스크 검증에서 실패하고, 거기에 붙는 .optional() 은 「속성이 비어도 된다」이지 「파일이 없어도 된다」가
// 아니다(Gradle 9 — ops-api 에서 `Input file does not exist` 로 확인했다). 파일 컬렉션 입력은 없는 파일을 「없음」
// 으로 지문에 넣으므로, 문서가 생기는 날 그 변화가 곧 입력 변화가 된다 — 「있으면 선언한다」를 구성 시점의
// exists() 로 적지 않는 이유다.
tasks.named<Test>("integrationTest") {
    inputs.files(rootProject.layout.projectDirectory.file("contracts/openapi/${project.name}.yaml"))
        .withPropertyName("openApiContract")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// -----------------------------------------------------------------------------
// 알림 걸린 닫힌 카운터는 기동 때 조합 전부가 있다 (DESIGN.md §9.1 「짝」, ADR-060 결정 3).
//
// 각 서비스의 OpenApiContractIT 가 libs/observability 의 AlertedCountersContract 를 구현한다. 대상은 사람이 적지 않고
// 규칙 파일 · §9.1(카탈로그의 라벨 칸 · 「emit 주체」)에서 뽑으므로, 그 둘을 입력으로 선언한다 — 규칙이나 표만 바꾼
// 실행에서 integrationTest 가 UP-TO-DATE 로 건너뛰면 새 대상이 검사 밖에 남는다. 서비스마다가 아니라 여기에 두는 이유는
// 위 OpenAPI 입력과 같다.
// -----------------------------------------------------------------------------
dependencies {
    add("integrationTestImplementation", testFixtures(project(":libs:observability")))
}

tasks.named<Test>("integrationTest") {
    inputs.file(rootProject.layout.projectDirectory.file("docs/DESIGN.md"))
        .withPropertyName("metricsTable")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.layout.projectDirectory.dir("deploy/compose/prometheus/rules"))
        .withPropertyName("alertRules")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// 라이브러리가 아니라 애플리케이션이므로 plain jar 는 만들지 않는다.
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveClassifier.set("")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootBuildImage>("bootBuildImage") {
    imageName.set("dawnline/${project.name}:${project.version}")
    environment.set(
        mapOf(
            // Buildpacks 가 JDK 25 런타임을 선택하도록 지정
            "BP_JVM_VERSION" to libs.findVersion("java").get().requiredVersion,
        ),
    )
}
