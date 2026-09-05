package com.dawnline.fulfillment.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.Topics;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 계획 결과 리스너의 토픽 이름이 §4.1 규칙과 같은지 (ADR-024). */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PlanResultListenerTopicsTest {

    @Test
    void 토픽_이름이_규칙과_같다() {
        assertThat(PlanResultListener.PLAN_COMPLETED_TOPIC)
                .isEqualTo(Topics.forEvent("plan.completed", 1));
        assertThat(PlanResultListener.PLAN_FAILED_TOPIC)
                .isEqualTo(Topics.forEvent("plan.failed", 1));
    }

    @Test
    void 늦은_plan_failed_의_사유_이름이_설계서와_같다() {
        // §5.2 와 ADR-024 결정 4 가 이 문자열을 적어 두었다. 메트릭 라벨이라 오타가 조용히 지나간다.
        assertThat(PlanResultListener.WAVE_ALREADY_PLANNED).isEqualTo("wave_already_planned");
    }
}
