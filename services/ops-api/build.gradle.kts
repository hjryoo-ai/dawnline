plugins {
    id("dawnline.spring-service")
}

dependencies {
    implementation(project(":libs:common"))
    implementation(project(":libs:messaging"))
    implementation(project(":libs:observability"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.security)
    // Boot 4 모듈화: FlywayAutoConfiguration 은 spring-boot-flyway 모듈에만 있다.
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.flyway.postgresql)
    implementation(libs.springdoc.openapi.webmvc)
    runtimeOnly(libs.postgresql)

    testImplementation(testFixtures(project(":libs:common")))
    // 순서 검사의 사실 집합을 계약 예시에서 만든다 (ADR-051 결정 6, 불변규칙 8).
    testImplementation(testFixtures(project(":libs:messaging")))

    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.awaitility)
}

// -----------------------------------------------------------------------------
// 테스트가 읽는 저장소 파일을 입력으로 선언한다 (CLAUDE.md — 아니면 문서·계약만 고친 빌드에서
// test 가 UP-TO-DATE 로 건너뛴다).
//   - contracts/events: 순서 검사의 사실 집합과 구독 토픽 대조가 계약 디렉터리에서 시작한다
//   - docs/DESIGN.md: 네 축의 선언 순서를 §5.5 「DDL 정정」 표와 대조한다
// -----------------------------------------------------------------------------
tasks.named<Test>("test") {
    inputs.dir(rootProject.layout.projectDirectory.dir("contracts/events"))
            .withPropertyName("eventContracts")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.layout.projectDirectory.file("docs/DESIGN.md"))
            .withPropertyName("design")
            .withPathSensitivity(PathSensitivity.RELATIVE)
}
