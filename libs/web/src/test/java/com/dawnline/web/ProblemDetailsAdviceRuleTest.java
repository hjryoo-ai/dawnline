package com.dawnline.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.archunit.HexagonalArchitectureRules;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit 규칙 9 — 「{@code @ControllerAdvice} 를 가진 서비스는 전부 이 기반을 쓴다」
 * (DESIGN.md §13, ADR-049 결정 4).
 *
 * <p><strong>표본이 여기 있는 이유</strong>: 규칙의 표본은 보통 {@code libs/common} 의
 * {@code archunit/samples} 에 모여 있는데, 양성 표본은 {@code ProblemDetailsAdviceSupport} 를
 * 상속해야 하므로 {@code libs/common} → {@code libs/web} 의존이 필요하다. 그 방향은 반대다
 * ({@code libs/web} 이 {@code libs/common} 을 쓴다). 그래서 이 규칙의 표본만 대상 타입이 사는
 * 모듈에 둔다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("규칙 9 — 오류 응답의 모양은 한 곳에서 온다")
class ProblemDetailsAdviceRuleTest {

    private static final ArchRule RULE = HexagonalArchitectureRules.ERROR_SHAPE_COMES_FROM_ONE_PLACE;

    @Test
    void 규칙이_가리키는_이름이_실제_클래스와_같다() {
        // 규칙은 기반 클래스를 문자열로 가리킨다(의존 방향 때문이다). 문자열 링크는 끊어져도
        // 조용하다 — 이름이 바뀌면 규칙은 아무것도 매치하지 않으면서 통과한다.
        // 「서로를 비추는 목록에는 대조 검사를 둔다」(DESIGN.md §13 규칙 3)의 자리다.
        assertThat(HexagonalArchitectureRules.ERROR_ADVICE_BASE)
                .isEqualTo(ProblemDetailsAdviceSupport.class.getName());
    }

    @Test
    void 기반을_쓰는_어드바이스는_통과한다() {
        // 반대 방향도 본다 — 없으면 「모든 어드바이스를 막는」 규칙이 되어도 테스트가 통과한다.
        RULE.check(new ClassFileImporter().importPackages("com.dawnline.web.samples.good"));
    }

    @Test
    void 기반_없이_어드바이스를_만들면_잡는다() {
        assertThatThrownBy(() ->
                RULE.check(new ClassFileImporter().importPackages("com.dawnline.web.samples.bad")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("OwnShapeAdvice")
                .hasMessageContaining(HexagonalArchitectureRules.ERROR_ADVICE_BASE);
    }
}
