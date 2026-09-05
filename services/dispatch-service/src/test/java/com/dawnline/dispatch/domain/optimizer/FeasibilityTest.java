package com.dawnline.dispatch.domain.optimizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.error.ValidationException;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FeasibilityTest {

    @Test
    void 통과는_사유를_들지_않는다() {
        assertThat(Feasibility.ok().feasible()).isTrue();
        assertThat(Feasibility.ok().ruleName()).isNull();
    }

    @Test
    void 불가는_사유를_반드시_든다() {
        // 사유 없는 거절은 §6.3 이 룰을 데이터로 둔 이유를 무너뜨린다.
        assertThatThrownBy(() -> new Feasibility(false, null, "이유"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Feasibility(false, "rule", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 통과에_사유를_붙이면_거부한다() {
        assertThatThrownBy(() -> new Feasibility(true, "rule", "이유"))
                .isInstanceOf(ValidationException.class);
    }
}
