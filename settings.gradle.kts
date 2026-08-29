pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // JDK 25 툴체인 자동 프로비저닝 (로컬에 JDK 25가 없어도 Gradle이 Temurin 25를 내려받는다)
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "dawnline"

// --- 플랫폼 라이브러리 -------------------------------------------------------
include(
    "libs:common",
    "libs:messaging",
    "libs:observability",
)

// --- 코어 서비스 -------------------------------------------------------------
include(
    "services:order-service",
    "services:fulfillment-service",
    "services:dispatch-service",
    "services:tracking-service",
    "services:ops-api",
)
