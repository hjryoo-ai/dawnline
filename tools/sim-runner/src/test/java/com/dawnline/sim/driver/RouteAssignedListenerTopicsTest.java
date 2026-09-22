package com.dawnline.sim.driver;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.Topics;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * 리스너의 토픽·id 가 규칙과 같은지 — tracking 의 같은 이름 테스트와 같은 이유다.
 * 오타는 컨슈머가 <strong>조용히 아무것도 받지 않는</strong> 형태로 나타난다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RouteAssignedListenerTopicsTest {

    private static KafkaListener annotation() throws NoSuchMethodException {
        Method method = RouteAssignedListener.class.getMethod("onRouteAssigned",
                org.apache.kafka.clients.consumer.ConsumerRecord.class);
        KafkaListener found = method.getAnnotation(KafkaListener.class);
        assertThat(found).isNotNull();
        return found;
    }

    @Test
    void 토픽_이름이_규칙과_같다() {
        assertThat(RouteAssignedListener.ROUTE_ASSIGNED_TOPIC)
                .isEqualTo(Topics.forEvent("route.assigned", 1));
    }

    @Test
    void 어노테이션이_그_상수를_쓴다() throws NoSuchMethodException {
        // 상수끼리만 비교하면 어노테이션에 다른 문자열이 박혀도 초록이다.
        assertThat(annotation().topics()).containsExactly(RouteAssignedListener.ROUTE_ASSIGNED_TOPIC);
    }

    @Test
    void 컨테이너는_꺼진_채로_등록된다() throws NoSuchMethodException {
        // 자동으로 뜨면 그 순간 Kafka 가 필수가 되어 smoke 가 브로커 없이 돌지 못한다.
        // 켜는 것은 DriverScenario 이고, 켜는 시점은 주문을 넣기 전이다.
        assertThat(annotation().autoStartup()).isEqualTo("false");
        assertThat(annotation().id()).isEqualTo(RouteAssignedListener.LISTENER_ID);
    }
}
