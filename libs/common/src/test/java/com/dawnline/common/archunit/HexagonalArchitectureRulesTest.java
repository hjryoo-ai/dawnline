package com.dawnline.common.archunit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("HexagonalArchitectureRules — 서비스가 공유하는 ArchUnit 규칙 (DESIGN.md §13)")
class HexagonalArchitectureRulesTest {

    private static final String SAMPLES = "com.dawnline.common.archunit.samples";

    private static final JavaClasses GOOD = new ClassFileImporter().importPackages(SAMPLES + ".good");
    private static final JavaClasses BAD = new ClassFileImporter().importPackages(SAMPLES + ".bad");
    private static final JavaClasses COMMON = new ClassFileImporter().importPackages("com.dawnline.common");

    @Test
    void 규칙1_규칙2_는_올바른_헥사고날_표본을_통과시킨다() {
        HexagonalArchitectureRules.DOMAIN_IS_FRAMEWORK_FREE.check(GOOD);
        HexagonalArchitectureRules.APPLICATION_DOES_NOT_DEPEND_ON_ADAPTER.check(GOOD);
    }

    @Test
    void 규칙1_은_domain_이_Spring_에_의존하면_실패한다() {
        // 금지 대상은 org.springframework.. 과 jakarta.persistence.. 두 패키지이고, 규칙은 그 둘을
        // 하나의 resideInAnyPackage 술어로 검사한다. 표본은 Spring 쪽을 건드린다 —
        // libs/common 의 test 클래스패스에 jakarta.persistence 가 없기 때문이다.
        // JPA 쪽은 같은 술어에 들어가는 다른 패키지 문자열일 뿐 검사 경로가 다르지 않다.
        assertThatThrownBy(() -> HexagonalArchitectureRules.DOMAIN_IS_FRAMEWORK_FREE.check(BAD))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("FrameworkBoundOrder")
                .hasMessageContaining("org.springframework");
    }

    @Test
    void 규칙2_는_application_이_adapter_를_참조하면_실패한다() {
        assertThatThrownBy(() -> HexagonalArchitectureRules.APPLICATION_DOES_NOT_DEPEND_ON_ADAPTER.check(BAD))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("LeakyUseCase")
                .hasMessageContaining("SampleRepository");
    }

    @Test
    void 규칙6_은_application_이_KafkaTemplate_을_직접_쓰면_실패한다() {
        // 불변규칙 1 의 유일한 자동 강제 수단이다. 이 표본이 컴파일된다는 사실 자체가 규칙의 존재 이유다 —
        // libs/messaging 이 Kafka 를 api 로 노출하므로 서비스 유스케이스에서도 똑같이 컴파일된다.
        assertThatThrownBy(() -> HexagonalArchitectureRules.PUBLISHING_GOES_THROUGH_OUTBOX_ONLY.check(BAD))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("KafkaCallingUseCase")
                .hasMessageContaining("KafkaTemplate");
    }

    @Test
    void 규칙6_은_올바른_표본을_통과시킨다() {
        HexagonalArchitectureRules.PUBLISHING_GOES_THROUGH_OUTBOX_ONLY.check(GOOD);
    }

    @Test
    void libs_common_자체가_Spring_과_JPA_에_의존하지_않는다() {
        // CLAUDE.md 불변규칙 5. 규칙 1과 같은 조건을 libs/common 전체에 적용해 본다.
        // samples 는 규칙이 "잡아야 할 것을 잡는지" 보려고 일부러 위반하는 표본이라 제외한다 —
        // 여기서 보려는 것은 libs/common 의 실제 코드다.
        ArchRule rule = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that()
                .resideInAPackage("com.dawnline.common..")
                .and()
                .resideOutsideOfPackage(SAMPLES + "..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.persistence..");

        rule.check(COMMON);
    }

    @ParameterizedTest
    @ValueSource(strings = {"order", "fulfillment", "dispatch", "tracking", "ops"})
    void 서비스별_규칙_5개를_만들_수_있고_대상이_없으면_통과한다(String service) {
        List<ArchRule> rules = HexagonalArchitectureRules.allRulesFor(service);

        assertThat(rules).hasSize(6);
        // 표본에는 com.dawnline.<service> 클래스가 없으므로 규칙 3·4·5 는 대상이 0개다.
        // (규칙 3·4·5 의 "잡아야 할 것을 잡는가" 는 아직 검증되지 않았다 — Phase 1 에서
        //  첫 @KafkaListener·@Transactional 이 생길 때 음성 표본을 함께 추가한다.)
        // allowEmptyShould(true) 덕분에 "대상 없음"이 실패가 되지 않는다.
        rules.forEach(rule -> rule.check(GOOD));
    }

    @Test
    void packageOf_는_com_dawnline_서비스명_이다() {
        assertThat(HexagonalArchitectureRules.packageOf("order")).isEqualTo("com.dawnline.order");
        assertThat(HexagonalArchitectureRules.BASE_PACKAGE).isEqualTo("com.dawnline");
        assertThat(HexagonalArchitectureRules.SERVICES)
                .containsExactly("order", "fulfillment", "dispatch", "tracking", "ops");
    }

    @Test
    void 규칙3_은_자기_서비스를_뺀_나머지_네_서비스를_금지_대상으로_삼는다() {
        String description = HexagonalArchitectureRules.noCrossServiceDependency("order").getDescription();

        assertThat(description)
                .contains("com.dawnline.order..")
                .contains("com.dawnline.fulfillment..")
                .contains("com.dawnline.dispatch..")
                .contains("com.dawnline.tracking..")
                .contains("com.dawnline.ops..");
        // 자기 자신은 대상(that 절)에만 한 번 나오고, 금지 목록에는 들어가지 않는다.
        assertThat(description.split(java.util.regex.Pattern.quote("com.dawnline.order.."), -1))
                .as("설명: %s", description)
                .hasSize(2);
    }

    @Test
    void 규칙4_는_KafkaListener_를_adapter_in_messaging_으로_제한한다() {
        String description = HexagonalArchitectureRules
                .kafkaListenersOnlyInInboundMessagingAdapter("tracking")
                .getDescription();

        assertThat(description)
                .contains("KafkaListener")
                .contains("com.dawnline.tracking.adapter.in.messaging..");
    }

    @Test
    void 규칙5_는_Transactional_을_application_계층으로_제한한다() {
        String description = HexagonalArchitectureRules
                .transactionalOnlyInApplicationLayer("dispatch")
                .getDescription();

        assertThat(description)
                .contains("Transactional")
                .contains("com.dawnline.dispatch.application..");
    }

    @Test
    void 알_수_없는_서비스명은_거부한다() {
        assertThatThrownBy(() -> HexagonalArchitectureRules.noCrossServiceDependency("order-service"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("알 수 없는 서비스");
        assertThatThrownBy(() -> HexagonalArchitectureRules.kafkaListenersOnlyInInboundMessagingAdapter("billing"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HexagonalArchitectureRules.transactionalOnlyInApplicationLayer(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HexagonalArchitectureRules.allRulesFor("ops-web"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HexagonalArchitectureRules.packageOf(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("service");
    }

    @Test
    void 유틸리티_클래스는_생성할_수_없다() throws NoSuchMethodException {
        Constructor<HexagonalArchitectureRules> constructor =
                HexagonalArchitectureRules.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance)
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(AssertionError.class);
    }
}
