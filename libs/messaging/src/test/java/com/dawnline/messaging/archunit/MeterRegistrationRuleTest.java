package com.dawnline.messaging.archunit;

import com.dawnline.common.archunit.HexagonalArchitectureRules;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit 규칙 11 — 미터는 {@code DawnlineMeters} 로만 등록한다(ADR-060 결정 2). {@code libs/messaging} 는 서비스가 아니라
 * {@code allRulesFor} 가 닿지 않으므로 이 모듈이 자기 패키지에 건다. 테스트 코드는 뺀다 — 규칙은 프로덕션 구조를 서술한다.
 */
class MeterRegistrationRuleTest {

    @Test
    void 미터는_헬퍼로만_등록한다() {
        HexagonalArchitectureRules.metersRegisterThroughCatalogue("com.dawnline.messaging")
                .check(new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.dawnline.messaging"));
    }
}
