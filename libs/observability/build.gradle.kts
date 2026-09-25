plugins {
    id("dawnline.java-conventions")
    `java-test-fixtures`
}

dependencies {
    api(project(":libs:common"))

    // 카탈로그(DawnlineMetrics)와 등록 헬퍼(DawnlineMeters)가 Micrometer 타입을 시그니처에 쓴다 — 부르는 쪽이 받는다.
    api(libs.micrometer.core)

    // 이 모듈은 libs/messaging · libs/web 이 참조한다(ADR-060 결정 2 — 미터는 이 모듈의 헬퍼로만 등록한다). 그래서
    // 웹이 아닌 소비자(tools/sim-runner)에게 서블릿 · OTel 스택을 끌고 가지 않는다.
    //  - OTel 스타터(트레이싱 · OTLP)는 dawnline.spring-service 규약이 다섯 서비스에 건다.
    //  - 웹은 compileOnly — MdcFilter 의 자동 설정은 @ConditionalOnWebApplication 이라 웹이 없는 클래스패스에서 조용히 빠진다.
    implementation(libs.spring.boot.starter)
    compileOnly(libs.spring.boot.starter.web) // MdcFilter(서블릿 필터), jakarta.servlet
    implementation(libs.logback.classic)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.web)
    // 카탈로그의 Micrometer 이름이 Prometheus 에서 표의 이름이 되는지 레지스트리로 확인한다(DawnlineMetricsTest).
    testImplementation(libs.micrometer.registry.prometheus)
    // 대시보드 JSON 과 규칙 파일을 구조로 읽는다(DashboardsConsistencyTest · AlertRulesConsistencyTest).
    testImplementation(libs.jackson.databind)
    testImplementation(libs.snakeyaml)
    // 규칙 11(미터는 헬퍼로만)의 대상 확인 — ArchUnit 규칙은 libs/common 의 testFixtures 에 산다.
    testImplementation(testFixtures(project(":libs:common")))

    // §9.1 표 파서 — 서비스의 IT 가 「알림 걸린 닫힌 카운터가 기동 때 있다」를 같은 방식으로 읽는다(ADR-060 결정 3).
    testFixturesApi(libs.micrometer.core)

    // PrometheusRulesIT — 실제 Prometheus 컨테이너가 테스트의 Micrometer 레지스트리를 긁는다(§9.1 「짝」의 재현).
    integrationTestImplementation(libs.testcontainers.junit.jupiter)
    integrationTestImplementation(libs.awaitility)
}

// -----------------------------------------------------------------------------
// 이 모듈의 테스트는 저장소의 문서와 배포 설정을 읽는다(ADR-060):
//  - DawnlineMetricsTest · DashboardsConsistencyTest · AlertRulesConsistencyTest 가 docs/DESIGN.md §9.1 · §9.4 를
//  - 두 대조 검사가 deploy/compose 의 대시보드 JSON 과 규칙 파일을
// 입력으로 선언하지 않으면 문서나 대시보드만 바꾼 실행에서 Gradle 이 test 를 UP-TO-DATE 로 건너뛴다 — 검사가 돌지 않는데
// 초록이고, 그것이 이 검사들이 막으려는 모양이다(CLAUDE.md 「서로를 비추는 목록」).
// -----------------------------------------------------------------------------
tasks.named<Test>("test") {
    inputs.file(rootProject.layout.projectDirectory.file("docs/DESIGN.md"))
        .withPropertyName("designDocument")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.layout.projectDirectory.dir("deploy/compose/grafana/dashboards"))
        .withPropertyName("grafanaDashboards")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.layout.projectDirectory.dir("deploy/compose/prometheus"))
        .withPropertyName("prometheusConfiguration")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// PrometheusRulesIT 는 규칙 파일 · promtool 단위 테스트를 컨테이너에 싣고, 이미지 태그를 .env.example 에서 읽는다.
tasks.named<Test>("integrationTest") {
    inputs.dir(rootProject.layout.projectDirectory.dir("deploy/compose/prometheus"))
        .withPropertyName("prometheusConfiguration")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.layout.projectDirectory.file("deploy/compose/.env.example"))
        .withPropertyName("composeImageTags")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
