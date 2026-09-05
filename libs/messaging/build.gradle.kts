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

    // 이 라이브러리는 Redis 를 참조하지 않는다. 릴레이 리더 락이 잠깐 Redis 를 썼고
    // (ADR-027 원 결정) 그 의존은 compileOnly 였는데, advisory lock 으로 옮기면서
    // 조정자가 이미 쓰고 있는 DataSource 가 되어 의존 자체가 사라졌다.

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
    // 런타임이 아니라 컴파일 의존이다 — OutboxLeaderLockIT 가 PGSimpleDataSource 를 직접 만든다.
    // advisory lock 은 세션에 걸리므로 인스턴스마다 다른 커넥션이어야 하고, 그것을 스프링 없이
    // 만들려면 드라이버의 DataSource 가 필요하다.
    integrationTestImplementation(libs.postgresql)
}

// -----------------------------------------------------------------------------
// 계약 파일을 test 태스크의 입력으로 선언한다 (CLAUDE.md 불변규칙 8).
//
// EventContractsTest 는 contracts/events/ 를 런타임에 읽는다. 그런데 그 디렉터리는 이 모듈의
// 소스도 리소스도 아니라서, Gradle 이 보기에는 스키마나 예시만 고친 빌드는 "입력이 안 바뀐" 빌드다.
// 그러면 test 가 UP-TO-DATE 로 건너뛰고, 깨진 계약이 로컬에서 초록으로 보인다.
// (CI 는 매번 새 체크아웃이라 걸리지만, 그때는 이미 커밋된 뒤다.)
tasks.named<Test>("test") {
    inputs.dir(rootProject.layout.projectDirectory.dir("contracts/events"))
            .withPropertyName("eventContracts")
            .withPathSensitivity(PathSensitivity.RELATIVE)
}
