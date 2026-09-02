package com.dawnline.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.archunit.HexagonalArchitectureRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;

/**
 * fulfillment-service 아키텍처 경계 테스트 (DESIGN.md §13, CLAUDE.md 불변규칙 3·4·5).
 *
 * <p>규칙 본문은 {@code libs/common} 의 테스트 픽스처
 * {@link HexagonalArchitectureRules} 가 모든 서비스에 공통으로 제공한다.
 * 규칙 6개: domain 프레임워크 비의존 · application→adapter 역참조 금지 ·
 * 서비스 간 참조 금지 · {@code @KafkaListener} 위치 · {@code @Transactional} 위치 ·
 * domain·application 의 Spring Kafka 의존 금지(발행은 Outbox 를 거친다, 불변규칙 1).
 *
 * <h2>골격 단계에서도 의미 있게 통과시키기</h2>
 * <p>규칙들은 {@code allowEmptyShould(true)} 라서 검사 대상이 아직 0개여도 실패하지 않는다
 * (Phase 0 에는 {@code @KafkaListener} 도 {@code @Transactional} 도 없다). 대신 규칙이
 * <b>조용히 무력화되는</b> 두 가지 경우를 두 번째 테스트가 직접 막는다.
 * <ol>
 *   <li>{@code @AnalyzeClasses} 의 패키지 문자열이 규칙이 검사하는 패키지와 어긋나는 경우</li>
 *   <li>임포트된 클래스가 하나도 없어 모든 규칙이 공허하게 통과하는 경우</li>
 * </ol>
 * 여기에 더해 §3.4 의 헥사고날 패키지가 실제로 존재하는지도 확인한다.
 */
@AnalyzeClasses(packages = "com.dawnline.fulfillment")
class ArchitectureTest {

    /** {@link HexagonalArchitectureRules#SERVICES} 의 서비스 식별자. */
    private static final String SERVICE = "fulfillment";

    /**
     * {@code @AnalyzeClasses} 에는 컴파일 상수만 넣을 수 있어 리터럴로 적고,
     * {@link HexagonalArchitectureRules#packageOf(String)} 와 같은지 아래에서 검증한다.
     */
    private static final String ANALYZED_PACKAGE = "com.dawnline.fulfillment";

    /** 헥사고날 레이아웃에서 존재해야 하는 패키지 접미사 (DESIGN.md §3.4). */
    private static final List<String> REQUIRED_PACKAGE_SUFFIXES = List.of(
            ".domain",
            ".application",
            ".application.port.in",
            ".application.port.out",
            ".adapter.in.web",
            ".adapter.in.messaging",
            ".adapter.out.persistence",
            ".config");

    @ArchTest
    static void 헥사고날_규칙_6개를_모두_지킨다(JavaClasses classes) {
        List<ArchRule> rules = HexagonalArchitectureRules.allRulesFor(SERVICE);
        assertThat(rules).as("DESIGN.md §13 의 ArchUnit 규칙 6개").hasSize(6);
        rules.forEach(rule -> rule.check(classes));
    }

    @ArchTest
    static void 분석_대상_패키지와_헥사고날_레이아웃이_실제로_존재한다(JavaClasses classes) {
        assertThat(ANALYZED_PACKAGE)
                .as("@AnalyzeClasses 가 보는 패키지와 규칙이 검사하는 패키지는 같아야 한다")
                .isEqualTo(HexagonalArchitectureRules.packageOf(SERVICE));

        assertThat(classes.size())
                .as("%s 에서 임포트된 클래스가 0개면 모든 규칙이 공허하게 통과한다", ANALYZED_PACKAGE)
                .isPositive();

        for (String suffix : REQUIRED_PACKAGE_SUFFIXES) {
            String required = ANALYZED_PACKAGE + suffix;
            assertThat(classes.containPackage(required))
                    .as("헥사고날 패키지 %s 가 있어야 한다 (DESIGN.md §3.4)", required)
                    .isTrue();
        }
    }
}
