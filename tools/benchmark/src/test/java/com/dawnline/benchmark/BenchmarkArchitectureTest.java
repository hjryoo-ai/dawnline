package com.dawnline.benchmark;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 이 모듈의 약속을 테스트가 지킨다 — <strong>서비스 없이 도메인만 실행한다</strong>.
 *
 * <p>{@code dispatch-service} 를 plain jar 로 받으므로 어댑터·설정 클래스도 클래스패스에 있다.
 * 컴파일이 막아 주지 않으므로(어댑터 중에는 Spring 타입이 서명에 없는 것도 있다) 여기서 막는다.
 * 이것이 무너지면 벤치마크는 "도메인을 그대로 실행한다" 가 아니라 "서비스의 일부를 실행한다" 가 되고,
 * 불변규칙 5 가 증명하려던 사실이 사라진다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class BenchmarkArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.dawnline.benchmark");

    @Test
    void 임포트된_클래스가_있다() {
        // 0개면 아래 규칙들이 공허하게 통과한다.
        org.assertj.core.api.Assertions.assertThat(CLASSES).isNotEmpty();
    }

    @Test
    void 벤치마크는_Spring_에_의존하지_않는다() {
        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.persistence..")
                .because("이 모듈의 존재 이유가 'domain.optimizer 를 서비스 없이 실행한다' 이다 (불변규칙 5)")
                .check(CLASSES);
    }

    @Test
    void 벤치마크는_dispatch_의_도메인만_쓴다() {
        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage(
                        "com.dawnline.dispatch.adapter..",
                        "com.dawnline.dispatch.application..",
                        "com.dawnline.dispatch.config..")
                .because("어댑터를 쓰기 시작하면 '서비스 없이' 가 아니다 (DESIGN.md §6.9)")
                .check(CLASSES);
    }
}
