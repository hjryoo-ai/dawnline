package com.dawnline.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.error.IllegalStateTransitionException;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CandidateStatusTest {

    @Test
    void 전이표_16개_조합이_설계서와_같다() {
        // 표를 통째로 고정한다. 한 칸만 고치는 변경이 나머지 열다섯을 조용히 바꾸지 못하게.
        String actual = Arrays.stream(CandidateStatus.values())
                .map(from -> from + "→" + Arrays.stream(CandidateStatus.values())
                        .filter(from::canTransitionTo).toList())
                .reduce("", (a, b) -> a + b + "\n");

        assertThat(actual).isEqualTo("""
                PENDING→[PLANNED, UNASSIGNED, CANCELLED]
                PLANNED→[CANCELLED]
                UNASSIGNED→[CANCELLED]
                CANCELLED→[]
                """);
    }

    @Test
    void 진행_축은_판정_1_취소_2_다() {
        assertThat(CandidateStatus.PENDING.progress()).isZero();
        assertThat(CandidateStatus.PLANNED.progress()).isEqualTo(1);
        assertThat(CandidateStatus.UNASSIGNED.progress()).isEqualTo(1);
        assertThat(CandidateStatus.CANCELLED.progress()).isEqualTo(2);
    }

    @Test
    void 판정끼리는_서로를_덮어쓰지_않는다() {
        // 같은 지점의 두 판정이다. 늦게 온 쪽이 이기면 결과가 도착 순서에 달린다.
        assertThat(CandidateStatus.PLANNED.hasProgressedPast(CandidateStatus.UNASSIGNED)).isTrue();
        assertThat(CandidateStatus.UNASSIGNED.hasProgressedPast(CandidateStatus.PLANNED)).isTrue();
    }

    @Test
    void 취소는_어디서든_앞으로_가는_전이다() {
        assertThat(CandidateStatus.PENDING.canTransitionTo(CandidateStatus.CANCELLED)).isTrue();
        assertThat(CandidateStatus.PLANNED.canTransitionTo(CandidateStatus.CANCELLED)).isTrue();
        assertThat(CandidateStatus.UNASSIGNED.canTransitionTo(CandidateStatus.CANCELLED)).isTrue();
    }

    @Test
    void 취소는_종결이라_되돌아가지_않는다() {
        assertThatThrownBy(() -> CandidateStatus.CANCELLED.transitionTo(CandidateStatus.PLANNED))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void 계획_대상은_PENDING_뿐이다() {
        assertThat(Arrays.stream(CandidateStatus.values())
                .filter(CandidateStatus::isPlannable).toList())
                .containsExactly(CandidateStatus.PENDING);
    }
}
