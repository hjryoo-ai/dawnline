plugins {
    id("dawnline.java-conventions")
    `java-test-fixtures`
}

// libs/common 은 프레임워크 비의존 순수 Java 다 (CLAUDE.md 불변규칙 5).
dependencies {
    // 서비스들이 공유하는 ArchUnit 규칙을 테스트 픽스처로 제공한다.
    testFixturesApi(libs.archunit.junit5)
}
