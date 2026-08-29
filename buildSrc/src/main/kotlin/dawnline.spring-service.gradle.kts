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

    add("testImplementation", libs.findLibrary("spring-boot-starter-test").get())
    add("integrationTestImplementation", libs.findLibrary("spring-boot-starter-test").get())
    add("integrationTestImplementation", libs.findLibrary("spring-boot-testcontainers").get())
    add("integrationTestImplementation", libs.findLibrary("testcontainers-junit-jupiter").get())

    // 아키텍처 경계 테스트 (DESIGN.md §13)
    add("testImplementation", libs.findLibrary("archunit-junit5").get())
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
