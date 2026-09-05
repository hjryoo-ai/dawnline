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

    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.testcontainers.redis)
}
