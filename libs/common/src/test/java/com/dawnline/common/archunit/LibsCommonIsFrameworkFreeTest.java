package com.dawnline.common.archunit;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit 규칙 10 — {@code libs/common} 의 {@code main} 은 프레임워크에 의존하지 않는다
 * (CLAUDE.md 불변규칙 5, DESIGN.md §13, [ADR-049] 결정 2).
 *
 * <p>{@code build.gradle.kts} 의 첫 줄에 「프레임워크 비의존 순수 Java 다」라고 적혀 있지만
 * <strong>그것은 문장이지 강제가 아니다</strong> — Spring 의존을 한 줄 추가하면 주석은 그대로
 * 있고 빌드는 통과한다. Spring 을 아는 공유 코드의 자리는 {@code libs/web} 이다.
 *
 * <p>이 모듈의 <em>테스트</em> 소스셋은 Spring 을 일부러 참조한다({@code samples/bad} 의 위반
 * 표본들이 컴파일되어야 음성 검증이 성립한다). 그래서 분석 대상을 {@code main} 출력으로 좁히고,
 * <strong>좁히기가 성공했는지를 첫 어설션이 말한다</strong> — 0 개를 읽으면 규칙은 아무것도
 * 검사하지 않으면서 통과하고, 그것이 이 규칙이 막으려는 모양이다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("규칙 10 — libs/common 의 main 은 순수 Java 다")
class LibsCommonIsFrameworkFreeTest {

    /**
     * Gradle 의 main 출력 디렉터리. 작업 디렉터리는 이 모듈의 프로젝트 루트다.
     *
     * <p>패키지 이름으로 읽지 <strong>않는</strong> 이유: 이 모듈의 테스트 클래스패스에서
     * {@code com.dawnline.common} 은 세 자리에 있다 — {@code classes/java/test} 디렉터리,
     * 테스트 픽스처 jar, 그리고 <em>main jar</em>. 패키지로 읽으면 셋이 다 들어오고, 그 안에는
     * Spring 을 일부러 참조하는 위반 표본이 있다. 경로로 읽으면 대상이 무엇인지가 한 줄로 보인다.
     */
    private static final Path MAIN_OUTPUT = Path.of("build/classes/java/main");

    private static final JavaClasses MAIN_CLASSES = new ClassFileImporter().importPath(MAIN_OUTPUT);

    @Test
    void main_출력을_실제로_읽었다() {
        // 전제다. 경로 모양이 바뀌어(예: Gradle 이 출력 자리를 옮겨) 0 개를 읽으면 아래 규칙은
        // 검사 없이 통과한다 — 「폴백 테스트는 전제를 첫 어설션으로 말한다」와 같은 축이다.
        assertThat(MAIN_CLASSES)
                .as("libs/common 의 main 클래스를 하나도 읽지 못했습니다. 경로(%s)와 "
                        + "테스트 작업 디렉터리(%s)를 확인하세요",
                        MAIN_OUTPUT, Path.of("").toAbsolutePath())
                .isNotEmpty();
        assertThat(MAIN_CLASSES.stream().map(javaClass -> javaClass.getName()))
                .as("그 안에 값 객체와 에러 모델이 있어야 한다")
                .contains("com.dawnline.common.Money", "com.dawnline.common.error.DomainException");
    }

    @Test
    void Spring_과_JPA_에_의존하지_않는다() {
        HexagonalArchitectureRules.LIBS_COMMON_MAIN_IS_FRAMEWORK_FREE.check(MAIN_CLASSES);
    }

    @Test
    void 테스트_소스셋은_이_규칙의_대상이_아니다() {
        // 음성 방향. samples/bad 는 Spring 을 일부러 참조하므로, 좁히기를 빼면 규칙이 깨져야 한다.
        // 깨지지 않는다면 그것은 표본이 사라졌거나 좁히기가 무의미하다는 뜻이다.
        JavaClasses everything = new ClassFileImporter().importPackages("com.dawnline.common.archunit.samples");

        assertThat(everything).as("전제: Spring 을 참조하는 표본이 테스트 소스셋에 있다").isNotEmpty();
        assertThat(everything.stream()
                .anyMatch(javaClass -> javaClass.getDirectDependenciesFromSelf().stream()
                        .anyMatch(dependency ->
                                dependency.getTargetClass().getPackageName().startsWith("org.springframework"))))
                .isTrue();
    }
}
