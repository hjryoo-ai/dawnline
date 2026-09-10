package com.dawnline.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.common.error.ValidationException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** §6.7 열화 판단 (ADR-034). */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PlanModeSelectorTest {

    private static final Duration BUDGET = Duration.ofSeconds(30);

    /** §6.7 의 두 수 그대로 — 랙 3 웨이브, 예산의 80%. */
    private final PlanModeSelector selector = new PlanModeSelector(3L, 0.8d);

    @Test
    void 랙이_임계를_넘으면_열화한다() {
        PlanModeSelector.Decision decision = selector.select(null, 4L, null, BUDGET);

        assertThat(decision.mode()).isEqualTo(PlanMode.FAST);
        assertThat(decision.reason()).isEqualTo(PlanModeReason.LAG);
    }

    @Test
    void 임계와_같으면_아직_열화하지_않는다() {
        // §6.7 은 "> 3 웨이브" 다. 경계를 어느 쪽으로 두는지는 설정 문서와 코드가 같아야 한다 —
        // 다르면 "3 으로 맞췄는데 왜 열화하나" 를 아무도 설명하지 못한다.
        PlanModeSelector.Decision decision = selector.select(null, 3L, null, BUDGET);

        assertThat(decision.mode()).isEqualTo(PlanMode.FULL);
        assertThat(decision.reason()).isEqualTo(PlanModeReason.NONE);
    }

    @Test
    void 직전_계획이_예산의_80퍼센트를_넘겼으면_열화한다() {
        PlanModeSelector.Decision decision =
                selector.select(null, 0L, Duration.ofMillis(24_001), BUDGET);

        assertThat(decision.mode()).isEqualTo(PlanMode.FAST);
        assertThat(decision.reason()).isEqualTo(PlanModeReason.BUDGET);
    }

    @Test
    void 정확히_80퍼센트는_넘긴_것이_아니다() {
        assertThat(selector.select(null, 0L, Duration.ofSeconds(24), BUDGET).mode())
                .isEqualTo(PlanMode.FULL);
    }

    @Test
    void 랙을_모르면_0으로_접지_않는다() {
        // 리밸런스 직후·운영자 재실행·정체 회수 — 볼 파티션이 없는 경로들이다. 모름을 0 으로
        // 접으면 랙 조건이 조용히 「아니오」가 되고, 판단이 멈춘 것과 정상이 구별되지 않는다
        // (ADR-027 의 세 상태와 같은 규칙).
        PlanModeSelector.Decision decision = selector.select(null, null, null, BUDGET);

        assertThat(decision.mode()).isEqualTo(PlanMode.FULL);
        assertThat(decision.reason())
                .as("모름은 열화 사유가 아니지만 NONE 도 아니다")
                .isEqualTo(PlanModeReason.LAG_UNKNOWN);
    }

    @Test
    void 랙을_몰라도_예산_조건은_본다() {
        // 조건 둘은 독립이다. 하나를 못 본다고 다른 하나까지 포기하면 운영자 재실행이
        // 「어떤 상황에서도 FULL」이 되어, 이미 밀린 캠프를 사람이 더 밀리게 만든다.
        PlanModeSelector.Decision decision =
                selector.select(null, null, Duration.ofSeconds(25), BUDGET);

        assertThat(decision.mode()).isEqualTo(PlanMode.FAST);
        assertThat(decision.reason()).isEqualTo(PlanModeReason.BUDGET);
    }

    @Test
    void 직전_계획이_없으면_그_조건은_발화하지_않는다() {
        assertThat(selector.select(null, 0L, null, BUDGET).reason())
                .isEqualTo(PlanModeReason.NONE);
    }

    @Test
    void 지정된_모드가_자동_판단을_이긴다() {
        // 조건 둘이 모두 발화하는 상황에서도 사람이 FULL 을 지정하면 FULL 이다.
        PlanModeSelector.Decision decision =
                selector.select(PlanMode.FULL, 999L, Duration.ofSeconds(29), BUDGET);

        assertThat(decision.mode()).isEqualTo(PlanMode.FULL);
        assertThat(decision.reason())
                .as("사람이 고른 것은 열화가 아니다 — 카운터에 들어가면 안 된다")
                .isEqualTo(PlanModeReason.REQUESTED);
        assertThat(decision.reason().isDegraded()).isFalse();
    }

    @Test
    void 지정된_FAST_도_열화로_세지_않는다() {
        assertThat(selector.select(PlanMode.FAST, 0L, null, BUDGET).reason().isDegraded())
                .isFalse();
    }

    @Test
    void 열화는_래치가_아니다() {
        // 상태를 들지 않으므로 조건이 사라지면 다음 계획이 곧바로 FULL 이다. "한 번 열화하면
        // 누가 되돌리는가" 라는 질문이 생기지 않는 것이 이 설계의 요점이다.
        assertThat(selector.select(null, 10L, null, BUDGET).mode()).isEqualTo(PlanMode.FAST);
        assertThat(selector.select(null, 0L, Duration.ofSeconds(1), BUDGET).mode())
                .isEqualTo(PlanMode.FULL);
    }

    @Test
    void 랙이_선행_지표라_예산보다_먼저_적힌다() {
        // 둘 다 발화하면 모드는 어차피 FAST 다. 갈리는 것은 <em>기록된 사유</em>이고, 먼저
        // 켜진 쪽을 적어야 "무엇이 이 열화를 시작했나" 를 나중에 읽을 수 있다.
        PlanModeSelector.Decision decision =
                selector.select(null, 9L, Duration.ofSeconds(29), BUDGET);

        assertThat(decision.reason()).isEqualTo(PlanModeReason.LAG);
    }

    @Test
    void 비율은_0_초과_1_이하여야_한다() {
        assertThatThrownBy(() -> new PlanModeSelector(3L, 0.0d))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new PlanModeSelector(3L, 1.5d))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new PlanModeSelector(-1L, 0.8d))
                .isInstanceOf(ValidationException.class);
    }
}
