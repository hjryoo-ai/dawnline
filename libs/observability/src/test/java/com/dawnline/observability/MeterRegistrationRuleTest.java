package com.dawnline.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.archunit.HexagonalArchitectureRules;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit 규칙 11 이 헬퍼의 자리를 <strong>문자열</strong>로 가리킨다(방향 때문에 — 규칙 9 와 같다). 문자열 링크는 끊어져도
 * 조용하다: 헬퍼를 옮기면 규칙은 헬퍼 자신까지 금지하거나(빌드가 빨갛다 — 드러난다), 새 자리의 우회를 허용한다(조용하다).
 * 그래서 대조한다(DESIGN.md §13).
 */
class MeterRegistrationRuleTest {

    @Test
    void 규칙11_이_가리키는_패키지가_헬퍼의_패키지다() {
        assertThat(HexagonalArchitectureRules.METER_HELPER_PACKAGE).isEqualTo(DawnlineMeters.class.getPackageName());
    }
}
