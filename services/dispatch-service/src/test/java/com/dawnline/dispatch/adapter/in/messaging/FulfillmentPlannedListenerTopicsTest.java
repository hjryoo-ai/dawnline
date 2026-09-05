package com.dawnline.dispatch.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.Topics;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 리스너의 토픽 이름이 §4.1 규칙과 같은지.
 *
 * <p>{@code @KafkaListener} 의 {@code topics} 는 컴파일 타임 상수여야 해서
 * {@code Topics.forEvent(...)} 를 부를 수 없다. 오타는 컨슈머가 <strong>조용히 아무것도 받지
 * 않는</strong> 형태로 나타나므로 눈으로 찾기 어렵다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FulfillmentPlannedListenerTopicsTest {

    @Test
    void 토픽_이름이_규칙과_같다() {
        assertThat(FulfillmentPlannedListener.FULFILLMENT_PLANNED_TOPIC)
                .isEqualTo(Topics.forEvent("fulfillment.planned", 1));
    }

    @Test
    void 소비자_이름이_서비스_이름이다() {
        // processed_events.consumer 값이다. 인스턴스마다 달라지면 멱등이 깨진다 (§8.5).
        assertThat(FulfillmentPlannedListener.CONSUMER).isEqualTo("dispatch-service");
    }
}
