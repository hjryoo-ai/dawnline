plugins {
    id("dawnline.spring-service")
}

/*
 * 이 서비스만 plain jar 를 만든다.
 *
 * dawnline.spring-service 는 "라이브러리가 아니라 애플리케이션" 이라 plain jar 를 끄는데,
 * dispatch-service 는 예외다 — `tools/benchmark` 가 domain.optimizer 를 **서비스를 띄우지 않고
 * 그대로** 실행하기 때문이다(DESIGN.md §6.9, 불변규칙 5). bootJar 는 BOOT-INF 로 감싸므로
 * 의존으로 쓸 수 없고, plain jar 가 없으면 project(...) 의존이 빈 아티팩트를 가리켜
 * NoClassDefFoundError 가 난다.
 *
 * 나머지 네 서비스는 그대로 꺼 둔다 — 라이브러리로 소비될 이유가 없고, 켜 두면 서비스 간 소스
 * 의존(불변규칙 3)이 컴파일에 성공하는 길이 열린다.
 */
tasks.named<Jar>("jar") {
    enabled = true
    archiveClassifier.set("plain")
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
    implementation(libs.springdoc.openapi.webmvc)
    runtimeOnly(libs.postgresql)

    // libs/common 의 공유 ArchUnit 규칙 (DESIGN.md §13)
    testImplementation(testFixtures(project(":libs:common")))
    // libs/messaging 의 이벤트 계약 픽스처 (불변규칙 8). 소비자는 examples 로 검증한다
    // (계약 README §3) — 페이로드 매핑이 계약 예시에서 그대로 돌아야 한다.
    testImplementation(testFixtures(project(":libs:messaging")))
    // Boot 4 모듈화: @WebMvcTest·@AutoConfigureMockMvc 는 spring-boot-starter-test 가 아니라
    // 이 모듈에 있다. order-service 는 integrationTest 에 걸었지만 여기서는 **단위** 소스셋이다 —
    // MockMvc 슬라이스는 Docker 없이 돌고, JaCoCo 게이트는 test 소스셋만 보기 때문이다.
    testImplementation(libs.spring.boot.webmvc.test)

    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.testcontainers.redis)
}

// -----------------------------------------------------------------------------
// 계약 파일을 단위 테스트의 입력으로 선언한다 (불변규칙 8).
// DispatchPayloadContractTest 가 contracts/events 를 런타임에 읽는데 이 선언이 없어서, 스키마나
// 예시만 고친 빌드에서 test 가 UP-TO-DATE 로 건너뛰고 있었다 (2026-09-19 발견 — order·fulfillment
// 에는 있었고 여기만 빠져 있었다, DESIGN.md §13 규칙 3).
tasks.named<Test>("test") {
    inputs.dir(rootProject.layout.projectDirectory.dir("contracts/events"))
            .withPropertyName("eventContracts")
            .withPathSensitivity(PathSensitivity.RELATIVE)
}
