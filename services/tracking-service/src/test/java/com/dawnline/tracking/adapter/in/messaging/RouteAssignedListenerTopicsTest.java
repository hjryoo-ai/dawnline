package com.dawnline.tracking.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.Topics;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * 리스너의 토픽·소비자 이름이 §4.1·§8.5 와 같은지.
 *
 * <p>{@code @KafkaListener} 의 {@code topics} 는 컴파일 타임 상수여야 해서
 * {@code Topics.forEvent(...)} 를 부를 수 없다. 그래서 리터럴로 적고 여기서 대조한다 —
 * 오타는 컨슈머가 <strong>조용히 아무것도 받지 않는</strong> 형태로 나타나므로 눈으로 찾기 어렵다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RouteAssignedListenerTopicsTest {

    @Test
    void 토픽_이름이_규칙과_같다() {
        assertThat(RouteAssignedListener.ROUTE_ASSIGNED_TOPIC)
                .isEqualTo(Topics.forEvent("route.assigned", 1));
    }

    @Test
    void 소비자_이름이_서비스_이름이다() {
        // processed_events.consumer 값이다. 인스턴스마다 달라지면 멱등이 깨진다 (§8.5).
        assertThat(RouteAssignedListener.CONSUMER).isEqualTo("tracking-service");
    }

    @Test
    void 메트릭_태그의_이벤트_타입이_계약의_이벤트_타입이다() {
        // dawnline_event_stale_total 의 라벨이라 오타가 조용히 지나가고, 대시보드는
        // "지난 개정이 한 번도 없었다" 처럼 보인다.
        assertThat(RouteAssignedListener.EVENT_TYPE).isEqualTo("route.assigned");
        assertThat(Topics.forEvent(RouteAssignedListener.EVENT_TYPE, 1))
                .isEqualTo(RouteAssignedListener.ROUTE_ASSIGNED_TOPIC);
    }
}
