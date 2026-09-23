plugins {
    id("dawnline.java-conventions")
}

// libs/web 은 Spring 을 **아는** 공유 조각이다 (ADR-049). 그것이 libs/common 과 갈리는 자리이고,
// libs/common 의 main 이 순수하게 남는지는 ArchUnit 규칙 10(LibsCommonIsFrameworkFreeTest)이 본다.
dependencies {
    api(project(":libs:common"))

    // ResponseEntityExceptionHandler · ProblemDetail · jakarta.servlet 이 여기서 온다.
    // api 인 이유: 하위 클래스를 만드는 쪽(서비스의 @RestControllerAdvice)이 이 타입들을
    // 시그니처에서 그대로 쓴다.
    api(libs.spring.boot.starter.web)

    // jspecify 와 Boot BOM 은 dawnline.java-conventions 가 이미 건다.

    testImplementation(libs.spring.boot.starter.test)
    // 규칙 9 의 표본과 대조 검사가 쓴다 (ADR-049 결정 4).
    testImplementation(testFixtures(project(":libs:common")))
    testImplementation(libs.archunit.junit5)
}
