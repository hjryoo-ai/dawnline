plugins {
    `kotlin-dsl`
}

dependencies {
    // 컨벤션 플러그인(dawnline.spring-service)에서 Spring Boot 플러그인을 적용하려면
    // 플러그인 아티팩트가 buildSrc 클래스패스에 있어야 한다.
    implementation(libs.spring.boot.gradle.plugin)
}
