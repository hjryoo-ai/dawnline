package com.dawnline.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.error.IllegalStateTransitionException;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PlanStatusTest {

    @Test
    void 전이표_25개_조합이_설계서와_같다() {
        String actual = Arrays.stream(PlanStatus.values())
                .map(from -> from + "→" + Arrays.stream(PlanStatus.values())
                        .filter(from::canTransitionTo).toList())
                .reduce("", (a, b) -> a + b + "\n");

        assertThat(actual).isEqualTo("""
                REQUESTED→[PLANNING]
                PLANNING→[PLANNED, FAILED]
                PLANNED→[PUBLISHED, FAILED]
                PUBLISHED→[]
                FAILED→[REQUESTED]
                """);
    }

    @Test
    void 실패는_재실행으로_되돌아간다() {
        // §5.3 "운영자 재실행 가능". 되돌아갈 자리가 없으면 그 경로가 코드에 없는 것이다.
        assertThat(PlanStatus.FAILED.canTransitionTo(PlanStatus.REQUESTED)).isTrue();
    }

    @Test
    void PLANNING_은_되돌아가지_않는다() {
        // 계획 하나는 트랜잭션 하나다 — PLANNING 은 커밋되지 않고, 크래시의 회수는 롤백과 wave.closed 재전달이다
        // (§5.3, ADR-024 후속 정정). 이 전이를 쓰던 정체 회수는 입력이 없어 지웠다.
        assertThat(PlanStatus.PLANNING.canTransitionTo(PlanStatus.REQUESTED)).isFalse();
    }

    @Test
    void 발행만_종결이다() {
        assertThat(Arrays.stream(PlanStatus.values()).filter(PlanStatus::isTerminal).toList())
                .containsExactly(PlanStatus.PUBLISHED);
    }

    @Test
    void 발행된_계획은_되돌아가지_않는다() {
        assertThatThrownBy(() -> PlanStatus.PUBLISHED.transitionTo(PlanStatus.REQUESTED))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void 계획하지_않고_발행할_수_없다() {
        assertThat(PlanStatus.PLANNING.canTransitionTo(PlanStatus.PUBLISHED)).isFalse();
    }
}
