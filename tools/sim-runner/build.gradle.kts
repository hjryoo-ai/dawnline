/*
 * sim-runner — 시나리오 CLI (DESIGN.md §5.6).
 *
 * dawnline.spring-service 를 쓰지 않는 이유: 그 규약은 웹 서비스용이라 actuator·prometheus·
 * ArchUnit 을 붙이고 컨테이너 이미지를 만든다. 이 모듈은 떠 있는 프로세스가 아니라
 * **실행하고 끝나는 CLI** 다.
 *
 * HTTP 는 JDK 의 java.net.http 를 쓴다 — RestClient 를 쓰려면 spring-boot-starter-web 이
 * Tomcat 까지 끌고 오는데, 서버를 띄우지 않는 도구에 서블릿 컨테이너를 넣을 이유가 없다.
 */
plugins {
    id("dawnline.java-conventions")
    id("org.springframework.boot")
}

dependencies {
    implementation(libs.spring.boot.starter)
    // Boot 4 의 기본 Jackson 은 3.x(tools.jackson.*)이고 어떤 스타터도 전이로 넣어 주지 않는다.
    implementation(libs.jackson.databind)

    // 기사 시뮬레이터는 route.assigned 를 구독한다 (Phase 5-2). 봉투(EventEnvelope)·이벤트 JSON·
    // 토픽 이름은 libs/messaging 이 정한 것을 그대로 쓴다 — 여기에 다시 적으면 같은 계약이 두 곳에
    // 생기고, 갈라졌을 때 조용한 쪽은 도구다. JPA 는 아래에서 모듈째 빼낸다.
    implementation(project(":libs:messaging"))

    testImplementation(libs.spring.boot.starter.test)

    integrationTestImplementation(libs.spring.boot.starter.test)
    integrationTestImplementation(libs.testcontainers.junit.jupiter)
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.awaitility)
    // IT 가 브로커에 넣는 route.assigned 가 계약에 맞는지 그 자리에서 검증한다 (불변규칙 8).
    // 픽스처가 계약과 어긋나면 "시뮬레이터는 도는데 실제 이벤트로는 안 되는" 상태가 된다.
    integrationTestImplementation(testFixtures(project(":libs:messaging")))
}

// 계약 파일을 integrationTest 태스크의 입력으로 선언한다 (CLAUDE.md 불변규칙 8).
// SimDriverIT 가 contracts/events/ 를 런타임에 읽으므로, 선언하지 않으면 스키마만 고친 실행에서
// Gradle 이 태스크를 UP-TO-DATE 로 건너뛴다. ScanContractTest 가 읽는 openapi 도 같은 이유다.
tasks.named<Test>("integrationTest") {
    inputs.dir(rootProject.layout.projectDirectory.dir("contracts/events"))
            .withPropertyName("eventContracts")
            .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.named<Test>("test") {
    inputs.dir(rootProject.layout.projectDirectory.dir("contracts/openapi"))
            .withPropertyName("openApiContracts")
            .withPathSensitivity(PathSensitivity.RELATIVE)
}

// -----------------------------------------------------------------------------
// 이 모듈에는 JPA 가 들어오지 않는다.
//
// libs/messaging 이 spring-boot-starter-data-jpa 를 쓰는 것은 IdempotentConsumer·OutboxRelay
// 뿐이고 이 도구에는 DB 가 없다. 들어오면 DataSourceAutoConfiguration 이
// "Failed to determine a suitable driver class" 로 기동을 막는다.
//
// **의존 하나에 exclude 를 거는 것으로는 부족하다.** 처음에는 implementation(project(...)) 에만
// 걸었는데, 나중에 추가한 integrationTestImplementation(testFixtures(project(...))) 가 같은
// 프로젝트를 다시 선언하면서 JPA 를 되가져왔다 — 제외는 *선언마다* 걸리기 때문이다. 그 상태는
// 단위 테스트에서 초록이었고(테스트 클래스패스는 깨끗했다) SimDriverIT 이 컨텍스트를 띄우고 나서야
// 드러났다. 선언이 늘어날 때마다 같은 한 줄을 기억해야 하는 규칙은 조용히 새므로, 모듈 전체에
// 한 번 선언한다.
//
// 자동설정 이름을 spring.autoconfigure.exclude 에 문자열로 적는 방법도 있지만, Boot 4 에서
// 자동설정 패키지가 재배치됐고 이름이 틀리면 기동이 "그런 클래스가 없다" 로 끝난다 —
// 클래스패스에서 빼면 libs/messaging 의 @ConditionalOnClass 가 알아서 꺼진다.
//
// **이 줄을 지우면 무엇이 잡는가**: MessagingDependencyTest(test 클래스패스)와
// MessagingDependencyIT(integrationTest 클래스패스)가 같은 어설션으로 잡고, 그 뒤 SimDriverIT 이
// 컨텍스트 기동에서 잡는다. 주석이 아니라 그 셋이 이 결정을 지킨다 —
// 실제로 떼어 보고 IT 가 빨갛게 되는 것을 확인했다(2026-09-22).
configurations.configureEach {
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-data-jpa")
}

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveClassifier.set("")
}

// 배포되는 서비스가 아니다. `make images` 가 서비스 5개만 만들도록 이미지 태스크를 끈다.
// Compose 안에서 시나리오를 돌릴 일이 생기면(Phase 7 피크) 그때 다시 켠다.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootBuildImage>("bootBuildImage") {
    enabled = false
}
