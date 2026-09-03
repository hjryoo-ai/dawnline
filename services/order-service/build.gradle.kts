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
    implementation(libs.spring.boot.starter.data.redis)
    // Boot 4 모듈화: FlywayAutoConfiguration 은 spring-boot-flyway 모듈에만 있다.
    // 이 스타터가 없으면 spring.flyway.* 가 죽은 설정이 되어 마이그레이션이 실행되지 않는다.
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.flyway.postgresql)
    implementation(libs.springdoc.openapi.webmvc)
    runtimeOnly(libs.postgresql)

    // libs/common 의 공유 ArchUnit 규칙 (DESIGN.md §13)
    testImplementation(testFixtures(project(":libs:common")))

    // libs/messaging 의 이벤트 계약 검증 픽스처 (CLAUDE.md 불변규칙 8)
    testImplementation(testFixtures(project(":libs:messaging")))

    // Boot 4 모듈화: @AutoConfigureMockMvc 는 spring-boot-starter-test 가 아니라 이 모듈에 있다.
    integrationTestImplementation(libs.spring.boot.webmvc.test)
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.testcontainers.redis)
}

// -----------------------------------------------------------------------------
// 계약 파일을 test 태스크의 입력으로 선언한다 (CLAUDE.md 불변규칙 8).
// 이유는 libs/messaging/build.gradle.kts 의 같은 블록과 같다 — contracts/ 는 이 모듈의 소스도
// 리소스도 아니라서, 스키마만 고친 빌드는 Gradle 이 보기에 "입력이 안 바뀐" 빌드가 되고
// OrderPlacedContractTest 가 UP-TO-DATE 로 건너뛴다.
tasks.named<Test>("test") {
    inputs.dir(rootProject.layout.projectDirectory.dir("contracts/events"))
            .withPropertyName("eventContracts")
            .withPathSensitivity(PathSensitivity.RELATIVE)
}
