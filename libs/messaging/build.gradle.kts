plugins {
    id("dawnline.java-conventions")
}

dependencies {
    api(project(":libs:common"))
    api(libs.spring.kafka)
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.micrometer.core)
    compileOnly(libs.spring.boot.starter.actuator)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.json.schema.validator)

    integrationTestImplementation(libs.spring.boot.starter.test)
    integrationTestImplementation(libs.spring.boot.testcontainers)
    integrationTestImplementation(libs.testcontainers.junit.jupiter)
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.flyway.core)
    integrationTestImplementation(libs.flyway.postgresql)
    integrationTestRuntimeOnly(libs.postgresql)
}
