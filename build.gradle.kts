/*
 * Dawnline 루트 빌드.
 * 실제 규약은 buildSrc 의 dawnline.java-conventions / dawnline.spring-service 에 있다.
 */
plugins {
    base
}

allprojects {
    group = "com.dawnline"
    version = providers.gradleProperty("dawnlineVersion").getOrElse("0.1.0-SNAPSHOT")
}

/** 전체 통합 테스트를 한 번에 실행한다: ./gradlew integrationTest */
tasks.register("integrationTest") {
    description = "모든 모듈의 Testcontainers 통합 테스트를 실행한다 (Docker 필요)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("integrationTest") })
}
