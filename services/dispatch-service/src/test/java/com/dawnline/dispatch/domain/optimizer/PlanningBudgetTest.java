package com.dawnline.dispatch.domain.optimizer;

import static com.dawnline.dispatch.domain.optimizer.OptimizerFixtures.START;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.error.ValidationException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PlanningBudgetTest {

    private final PlanningBudget budget =
            new PlanningBudget(Duration.ofSeconds(30), Duration.ofSeconds(5));

    @Test
    void 마감은_시작에_전체_예산을_더한_시각이다() {
        assertThat(budget.deadlineFrom(START)).isEqualTo(START.plusSeconds(30));
    }

    @Test
    void 마감_직전에는_예산이_남아_있다() {
        assertThat(budget.hasRemaining(START, START.plusSeconds(29))).isTrue();
    }

    @Test
    void 마감_시각에는_예산이_없다() {
        // 경계에서 한 번 더 도는 것을 막는다 — 개선 단계는 예산을 끝까지 쓴다.
        assertThat(budget.hasRemaining(START, START.plusSeconds(30))).isFalse();
    }

    @Test
    void 라우트별_예산이_전체보다_클_수_없다() {
        assertThatThrownBy(() -> new PlanningBudget(Duration.ofSeconds(5), Duration.ofSeconds(30)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void 영_예산은_거부한다() {
        assertThatThrownBy(() -> new PlanningBudget(Duration.ZERO, Duration.ZERO))
                .isInstanceOf(ValidationException.class);
    }
}
