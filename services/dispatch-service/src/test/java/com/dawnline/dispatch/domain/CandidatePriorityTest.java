package com.dawnline.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.error.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 우선도 점수표 (ADR-028). */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("CandidatePriority — 우선도는 파생이다")
class CandidatePriorityTest {

    /** 설정 기본값과 같은 값이다 (`dawnline.dispatch.priority`). */
    private final CandidatePriority scale = new CandidatePriority(2, 1);

    @Test
    void 사실이_없으면_0_이다() {
        assertThat(scale.scoreOf(false, false)).isZero();
    }

    @Test
    void 사실마다_점수가_다르다() {
        // 두 값이 같으면 "무엇 때문에 우선인가" 를 점수로 되짚을 수 없다. 개정이 더 무겁다 —
        // 냉장은 <미배정의 대가>가 큰 것이고 개정은 <이미 깬 약속>이다 (ADR-020).
        assertThat(scale.scoreOf(true, false)).isEqualTo(2);
        assertThat(scale.scoreOf(false, true)).isEqualTo(1);
    }

    @Test
    void 사실이_쌓이면_점수도_쌓인다() {
        assertThat(scale.scoreOf(true, true)).isEqualTo(3);
    }

    @Test
    void 음수_가중치는_거부한다() {
        // 점수는 실망의 누적이지 감점이 아니다. 음수를 허용하면 우선도가 음수가 되고,
        // UNASSIGNED_PENALTY 의 `base + perPriority × priority` 가 기본값 아래로 내려간다.
        assertThatThrownBy(() -> new CandidatePriority(-1, 1))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new CandidatePriority(2, -1))
                .isInstanceOf(ValidationException.class);
    }
}
