plugins {
    id("dawnline.java-conventions")
    `java-test-fixtures`
}

// libs/common 은 프레임워크 비의존 순수 Java 다 (CLAUDE.md 불변규칙 5).
dependencies {
    // 서비스들이 공유하는 ArchUnit 규칙을 테스트 픽스처로 제공한다.
    testFixturesApi(libs.archunit.junit5)

    // ArchUnit 규칙이 "잡아야 할 것을 실제로 잡는지" 확인하려면, 위반 표본이 금지 대상 타입을
    // 진짜로 참조해야 한다. 컴파일되지 않는 표본으로는 음성 테스트를 쓸 수 없다.
    // test 스코프 전용이므로 main 은 프레임워크 비의존 그대로다 (불변규칙 5).
    testImplementation(libs.spring.boot.starter.kafka)
}
