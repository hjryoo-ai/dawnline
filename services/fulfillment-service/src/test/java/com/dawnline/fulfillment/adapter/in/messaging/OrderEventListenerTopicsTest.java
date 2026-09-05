package com.dawnline.fulfillment.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.Topics;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 리스너의 토픽 이름이 §4.1 규칙과 같은지.
 *
 * <p>{@code @KafkaListener} 의 {@code topics} 는 컴파일 타임 상수여야 해서
 * {@code Topics.forEvent(...)} 를 부를 수 없다. 그래서 리터럴로 적고 여기서 대조한다 —
 * 오타는 컨슈머가 <strong>조용히 아무것도 받지 않는</strong> 형태로 나타나므로 눈으로 찾기 어렵다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OrderEventListenerTopicsTest {

    @Test
    void 토픽_이름이_규칙과_같다() {
        assertThat(OrderEventListener.ORDER_PLACED_TOPIC)
                .isEqualTo(Topics.forEvent("order.placed", 1));
        assertThat(OrderEventListener.ORDER_CANCELLED_TOPIC)
                .isEqualTo(Topics.forEvent("order.cancelled", 1));
    }

    @Test
    void 취소_선착_거부_사유의_이름이_설계서와_같다() {
        // §4.6 표와 ADR-022 가 이 문자열을 적어 두었다. 메트릭 라벨이라 오타가 조용히 지나가고,
        // 대시보드는 "그런 사유가 한 번도 없었다" 처럼 보인다.
        assertThat(OrderEventListener.CANCELLED_BEFORE_PLACED).isEqualTo("cancelled_before_placed");
    }

    @Test
    void 소비자_이름이_서비스_이름이다() {
        // processed_events.consumer 값이다. 인스턴스마다 달라지면 멱등이 깨진다 (§8.5).
        assertThat(OrderEventListener.CONSUMER).isEqualTo("fulfillment-service");
    }
}
