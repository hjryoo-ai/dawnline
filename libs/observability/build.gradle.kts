plugins {
    id("dawnline.java-conventions")
}

dependencies {
    api(project(":libs:common"))

    // Boot 4 모듈화 주의: 트레이싱 자동설정은 spring-boot-micrometer-tracing-opentelemetry /
    // spring-boot-opentelemetry 에 있다. 이 스타터가 그것들과 micrometer-tracing-bridge-otel,
    // opentelemetry-exporter-otlp, micrometer-registry-otlp 를 한 번에 가져온다.
    // 빼면 Tracer 빈도 MDC 의 traceId 도 OTLP 내보내기도 생기지 않는다 (DESIGN.md §9.2).
    api(libs.spring.boot.starter.opentelemetry)

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.web) // MdcFilter(서블릿 필터), jakarta.servlet
    implementation(libs.micrometer.core)
    implementation(libs.logback.classic)

    testImplementation(libs.spring.boot.starter.test)
}
