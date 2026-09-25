plugins {
    id("dawnline.java-conventions")
    `java-test-fixtures`
}

// libs/common 은 프레임워크 비의존 순수 Java 다 (CLAUDE.md 불변규칙 5).
dependencies {
    // 서비스들이 공유하는 ArchUnit 규칙을 테스트 픽스처로 제공한다.
    testFixturesApi(libs.archunit.junit5)

    // OpenApiResponses 가 생성된 OpenAPI 문서를 구조로 읽는다 (서비스들의 OpenApiContractIT).
    // 문자열 포함으로는 "404 가 문서에 있는가" 까지만 답할 수 있고, "그 404 의 본문이 무엇인가" 는
    // 답할 수 없다 — 그 자리에서 실제로 결함이 나왔다(order-service.yaml 의 404 = OrderView).
    // testFixtures 스코프이므로 main 은 프레임워크 비의존 그대로다 (불변규칙 5).
    testFixturesImplementation(libs.jackson.databind)

    // ArchUnit 규칙이 "잡아야 할 것을 실제로 잡는지" 확인하려면, 위반 표본이 금지 대상 타입을
    // 진짜로 참조해야 한다. 컴파일되지 않는 표본으로는 음성 테스트를 쓸 수 없다.
    // test 스코프 전용이므로 main 은 프레임워크 비의존 그대로다 (불변규칙 5).
    testImplementation(libs.spring.boot.starter.kafka)
    // 규칙 8(ADR-009, 매핑에 리터럴 버전 금지)의 위반 표본이 @RequestMapping 을 실제로 붙여야
    // 한다. 같은 이유다 — 컴파일되지 않는 표본으로는 음성 테스트를 쓸 수 없다.
    testImplementation(libs.spring.boot.starter.web)
    // 규칙 11(미터는 DawnlineMeters 로만, ADR-060)의 위반 표본이 Micrometer 를 실제로 불러야 한다 — 같은 이유다.
    testImplementation(libs.micrometer.core)
}

// -----------------------------------------------------------------------------
// AdrIndexConsistencyTest 는 저장소의 문서를 읽는다(파일 · DESIGN §16 · adr/README).
// 입력으로 선언하지 않으면 **문서만 바꾼 실행에서 Gradle 이 test 를 UP-TO-DATE 로 건너뛴다** —
// 검사가 돌지 않는데 초록이고, 그것이 바로 이 테스트가 막으려는 모양이다 (DESIGN.md §13).
// -----------------------------------------------------------------------------
tasks.named<Test>("test") {
    inputs.file(rootProject.layout.projectDirectory.file("docs/DESIGN.md"))
        .withPropertyName("designDocument")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.layout.projectDirectory.dir("docs/adr"))
        .withPropertyName("adrDirectory")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// -----------------------------------------------------------------------------
// CarryOverLedgerConsistencyTest 는 저장소 전체에서 이월 표기를 센다(계획서 7-0 의 원천 목록 — 그 정규식).
// 같은 이유로 그 전부를 입력으로 선언한다 — 어느 파일에 표기가 하나 늘어도 test 가 다시 돈다.
// 빼는 것은 테스트와 같다: 빌드 산출물 · 의존성 · 숨은 디렉터리(.github 는 읽는다) · 로컬 전용 .env.
// 산출물을 빼지 않으면 다른 태스크의 출력이 이 태스크의 입력이 되어 Gradle 이 암묵적 의존으로 거부한다.
// -----------------------------------------------------------------------------
tasks.named<Test>("test") {
    inputs.files(
        rootProject.fileTree(rootProject.layout.projectDirectory) {
            exclude("**/build/**", "**/node_modules/**", "**/dist/**", "**/.*/**", "**/.env")
        },
    )
        .withPropertyName("phase7CarryOverSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.layout.projectDirectory.dir(".github"))
        .withPropertyName("ciConfiguration")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
