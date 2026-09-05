package com.dawnline.dispatch.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.Topics;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** 토픽 이름이 §4.1 규칙과 같은지. 오타는 컨슈머가 조용히 아무것도 받지 않는 형태로 나타난다. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WaveClosedListenerTopicsTest {

    @Test
    void 토픽_이름이_규칙과_같다() {
        assertThat(WaveClosedListener.WAVE_CLOSED_TOPIC)
                .isEqualTo(Topics.forEvent("wave.closed", 1));
    }

    @Test
    void 두_리스너가_같은_소비자_이름을_쓴다() {
        // processed_events.consumer 가 갈라지면 같은 이벤트를 두 번 처리한다 (§8.5).
        assertThat(WaveClosedListener.CONSUMER)
                .isEqualTo(FulfillmentPlannedListener.CONSUMER)
                .isEqualTo("dispatch-service");
    }
}
