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

    // 계약 예시로 소비자 페이로드를 검증한다 (불변규칙 8) — 발행자 쪽 테스트와 같은 픽스처다.
    testImplementation(testFixtures(project(":libs:messaging")))

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
