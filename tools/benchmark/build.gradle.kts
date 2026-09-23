/*
 * benchmark — 전략 비교 CLI (DESIGN.md §6.9).
 *
 * Spring 이 없다. 이 모듈의 존재 이유가 "domain.optimizer 를 서비스 없이 그대로 실행한다" 이고,
 * 여기에 Spring 이 들어오는 순간 그 주장이 증명되지 않기 때문이다(불변규칙 5).
 *
 * dispatch-service 를 implementation 으로 받는다 — Gradle 은 implementation 의존을 소비자에게
 * 노출하지 않으므로, 이 모듈의 **컴파일 클래스패스에는 Spring 이 없다.** 도메인이 프레임워크에
 * 기대는 순간 여기서 컴파일이 깨진다.
 *
 * 실행: ./gradlew :tools:benchmark:run --args='--dataset small --strategies baseline-nn'
 */
plugins {
    id("dawnline.java-conventions")
    application
}

dependencies {
    implementation(project(":services:dispatch-service"))
    implementation(project(":libs:common"))
    // 룰 시드 JSON 을 읽는 데만 쓴다. 도메인은 이미 파싱된 Map 을 받는다.
    // 버전은 java-conventions 가 붙인 Boot BOM 에서 온다.
    implementation(libs.jackson.databind)

    // "서비스 없이 도메인만" 이라는 이 모듈의 약속을 테스트가 지킨다.
    testImplementation(libs.archunit.junit5)
}

application {
    mainClass.set("com.dawnline.benchmark.BenchmarkMain")
}

// 기본 룰 시드 경로(contracts/seed/…)가 저장소 루트 기준이다. Gradle 의 run 은 모듈 디렉터리에서
// 도는 것이 기본이라, 문서에 적힌 명령(CLAUDE.md 「명령어」)이 그대로 돌게 맞춰 준다.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

// -----------------------------------------------------------------------------
// DeliveryStatusFanoutTest 는 저장소의 문서를 읽는다(측정 조건 문서 · DESIGN §8.2).
// 입력으로 선언하지 않으면 **문서만 바꾼 실행에서 Gradle 이 test 를 UP-TO-DATE 로 건너뛴다** —
// 검사가 돌지 않는데 초록이고, 그것이 바로 이 테스트가 막으려는 모양이다(DESIGN.md §13).
// libs/common 이 AdrIndexConsistencyTest 에 같은 것을 걸어 두었다.
// -----------------------------------------------------------------------------
tasks.named<Test>("test") {
    inputs.file(rootProject.layout.projectDirectory.file(
        "docs/benchmarks/phase5-delivery-status-throughput.md"))
        .withPropertyName("throughputConditions")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.layout.projectDirectory.file("docs/DESIGN.md"))
        .withPropertyName("designDocument")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
