plugins {
    id("dawnline.java-conventions")
}

dependencies {
    api(project(":libs:common"))
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.tracing.bridge.otel)
    implementation(libs.logback.classic)

    testImplementation(libs.spring.boot.starter.test)
}
