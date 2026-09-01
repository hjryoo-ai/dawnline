import org.gradle.api.tasks.testing.logging.TestExceptionFormat

/*
 * 모든 Dawnline 모듈의 공통 Java 규약.
 *  - JDK 25 툴체인 (DESIGN.md §11)
 *  - Spring Boot BOM 을 platform 으로 적용해 버전을 한 곳에서 관리 (CLAUDE.md)
 *  - integrationTest 소스셋 분리 (*IT.java, DESIGN.md §13)
 *  - JaCoCo 커버리지 게이트 (DESIGN.md §13)
 */

plugins {
    `java-library`
    `jacoco`
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.findVersion("java").get().requiredVersion))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
}

// -----------------------------------------------------------------------------
// integrationTest 소스셋 (Testcontainers, *IT.java)
// -----------------------------------------------------------------------------
val integrationTest: SourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets["main"].output + sourceSets["test"].output
    runtimeClasspath += sourceSets["main"].output + sourceSets["test"].output
}

configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
    // Boot BOM: 모든 컴파일/런타임/테스트 클래스패스에 적용
    add("api", platform(libs.findLibrary("spring-boot-bom").get()))
    add("testImplementation", platform(libs.findLibrary("spring-boot-bom").get()))
    add("integrationTestImplementation", platform(libs.findLibrary("spring-boot-bom").get()))

    // 널 가능성 명시 (CLAUDE.md 코딩 컨벤션)
    add("api", libs.findLibrary("jspecify").get())

    add("testImplementation", libs.findLibrary("junit-jupiter").get())
    add("testImplementation", libs.findLibrary("assertj-core").get())
    add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // record/생성자 파라미터 이름 보존 (Spring 바인딩, Jackson)
    // -Werror 로 경고를 차단하되, 프레임워크 코드(JPA 엔티티·Spring 설정)에서
    // 구조적으로 발생하는 this-escape 는 제외한다.
    options.compilerArgs.addAll(
        listOf("-parameters", "-Xlint:all,-serial,-processing,-this-escape", "-Werror"),
    )
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        showStackTraces = true
    }
    // 결정론: 최적화 테스트는 seed 고정, 시간은 Clock 주입 (DESIGN.md §13)
    systemProperty("user.timezone", "UTC")
    systemProperty("java.util.logging.manager", "java.util.logging.LogManager")
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    description = "Testcontainers 기반 통합 테스트 (*IT.java). Docker 필요."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.named("test"))
    // 로컬 실행 시간 단축 (DESIGN.md §13)
    systemProperty("testcontainers.reuse.enable", "true")
}

// -----------------------------------------------------------------------------
// JaCoCo 커버리지 게이트 (DESIGN.md §13)
//   기본 70%. optimizer 처럼 더 높은 기준이 필요한 모듈은 각 build.gradle.kts 에서
//   dawnlineCoverage.minimum 을 올린다.
// -----------------------------------------------------------------------------
interface DawnlineCoverageExtension {
    val minimum: Property<String>
    val excludes: ListProperty<String>
}

val coverage = extensions.create<DawnlineCoverageExtension>("dawnlineCoverage")
coverage.minimum.convention("0.70")
coverage.excludes.convention(
    listOf(
        "**/*Application*",          // Spring Boot 부트스트랩
        "**/config/**",              // 프레임워크 배선
        "**/adapter/in/web/dto/**",  // 직렬화 전용 DTO
    ),
)

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(
        files(classDirectories.files.map { fileTree(it) { exclude(coverage.excludes.get()) } }),
    )
}

val coverageVerification = tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("test"))
    classDirectories.setFrom(
        files(classDirectories.files.map { fileTree(it) { exclude(coverage.excludes.get()) } }),
    )
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = coverage.minimum.map { it.toBigDecimal() }.get()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestReport"), coverageVerification)
}
