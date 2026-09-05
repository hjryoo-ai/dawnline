package com.dawnline.dispatch.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.Topics;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 리스너의 토픽 이름이 §4.1 규칙과 같은지.
 *
 * <p>오타는 컨슈머가 <strong>조용히 아무것도 받지 않는</strong> 형태로 나타난다 — 이 리스너에서는
 * "취소가 라우트에 반영되지 않는다" 로 보이고, 그것을 발견하는 곳은 배송 현장이다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OrderCancelledListenerTopicsTest {

    @Test
    void 토픽_이름이_규칙과_같다() {
        assertThat(OrderCancelledListener.ORDER_CANCELLED_TOPIC)
                .isEqualTo(Topics.forEvent("order.cancelled", 1));
    }

    @Test
    void 소비자_이름이_다른_리스너와_같다() {
        // processed_events.consumer 는 서비스 단위다 (§8.5). 리스너마다 다르게 두면 같은
        // eventId 가 두 번 처리될 수 있고, 멱등의 근거가 사라진다.
        assertThat(OrderCancelledListener.CONSUMER)
                .isEqualTo(FulfillmentPlannedListener.CONSUMER);
    }
}
