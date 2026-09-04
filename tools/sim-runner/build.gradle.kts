/*
 * sim-runner — 시나리오 CLI (DESIGN.md §5.6).
 *
 * dawnline.spring-service 를 쓰지 않는 이유: 그 규약은 웹 서비스용이라 actuator·prometheus·
 * ArchUnit 을 붙이고 컨테이너 이미지를 만든다. 이 모듈은 떠 있는 프로세스가 아니라
 * **실행하고 끝나는 CLI** 다.
 *
 * 의존은 셋뿐이다. HTTP 는 JDK 의 java.net.http 를 쓴다 — RestClient 를 쓰려면
 * spring-boot-starter-web 이 Tomcat 까지 끌고 오는데, 서버를 띄우지 않는 도구에 서블릿
 * 컨테이너를 넣을 이유가 없다.
 */
plugins {
    id("dawnline.java-conventions")
    id("org.springframework.boot")
}

dependencies {
    implementation(libs.spring.boot.starter)
    // Boot 4 의 기본 Jackson 은 3.x(tools.jackson.*)이고 어떤 스타터도 전이로 넣어 주지 않는다.
    implementation(libs.jackson.databind)

    testImplementation(libs.spring.boot.starter.test)
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
