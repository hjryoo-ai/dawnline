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
    compileOnly(libs.spring.boot.starter.actuator)

    // 이벤트 계약 검증 픽스처 — 서비스들의 계약 테스트가 재사용한다 (CLAUDE.md 불변규칙 8).
    testFixturesApi(libs.json.schema.validator)
    testFixturesApi(libs.jackson.databind)

    testImplementation(libs.spring.boot.starter.test)

    integrationTestImplementation(libs.spring.boot.starter.test)
    integrationTestImplementation(libs.spring.boot.testcontainers)
    integrationTestImplementation(libs.testcontainers.junit.jupiter)
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.spring.boot.starter.flyway)
    integrationTestImplementation(libs.flyway.postgresql)
    integrationTestImplementation(libs.awaitility)
    integrationTestRuntimeOnly(libs.postgresql)
}
