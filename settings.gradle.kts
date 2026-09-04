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

// --- 도구 --------------------------------------------------------------------
// sim-runner 는 서비스가 아니라 REST 로 부하·시나리오를 만드는 CLI 다 (DESIGN.md §5.6).
// 코어 서비스 DB 나 토픽에 직접 붙지 않는다 — 불변규칙 3·4 는 도구에도 그대로다.
include(
    "tools:sim-runner",
)
