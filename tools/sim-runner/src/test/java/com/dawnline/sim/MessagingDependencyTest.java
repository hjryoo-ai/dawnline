package com.dawnline.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dawnline.messaging.Topics;
import com.dawnline.messaging.json.EventJson;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * {@code libs/messaging} 을 어떻게 끌어왔는가 — 봉투는 가져오고 JPA 는 두고 온다.
 *
 * <p>이 도구에는 DB 가 없다. {@code spring-boot-starter-data-jpa} 가 런타임 클래스패스에 남으면
 * {@code DataSourceAutoConfiguration} 이 "url 이 없다" 로 기동을 막는다. 그 결정은
 * {@code build.gradle.kts} 의 {@code exclude} 한 줄이고, 한 줄은 조용히 사라진다 —
 * 그래서 여기서 못박는다. {@code SimDriverIT} 도 같은 것을 보지만 그쪽은 Docker 가 필요하다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class MessagingDependencyTest {

    @Test
    void 봉투와_토픽_규칙은_libs_messaging_것을_쓴다() {
        // 여기에 다시 적으면 같은 계약이 두 곳에 생기고, 갈라졌을 때 조용한 쪽은 도구다.
        assertThat(Topics.forEvent("route.assigned", 1)).isEqualTo("dawnline.route.assigned.v1");
        assertThat(EventJson.standardMapper()).isNotNull();
    }

    @Test
    void JPA_는_클래스패스에_없다() {
        assertThatThrownBy(() -> Class.forName("jakarta.persistence.EntityManagerFactory"))
                .as("sim-runner 는 DB 가 없다. JPA 가 들어오면 DataSourceAutoConfiguration 이 기동을 막는다")
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void Kafka_는_클래스패스에_있다() {
        // 위 어설션이 "의존을 통째로 지웠다" 로도 통과하지 않게 반대 방향을 함께 본다 —
        // 가져오려던 것은 실제로 왔는가.
        assertThat(classExists("org.apache.kafka.clients.consumer.ConsumerRecord")).isTrue();
        assertThat(classExists("org.springframework.kafka.annotation.KafkaListener")).isTrue();
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }
}
