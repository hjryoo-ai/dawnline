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
 * {@code DataSourceAutoConfiguration} 이 "url 이 없다" 로 기동을 막는다.
 *
 * <h2>어설션은 한 벌, 클래스패스는 둘</h2>
 * 처음에는 이 클래스만 있었고, 그때 제외는 {@code implementation(project(…))} <em>선언 하나</em>에
 * 걸려 있었다. 뒤에 추가한 {@code integrationTestImplementation(testFixtures(project(…)))} 가 같은
 * 프로젝트를 다시 선언하며 JPA 를 되가져왔는데 — Gradle 의 {@code exclude} 는 선언마다 걸린다 —
 * <strong>이 테스트는 {@code test} 클래스패스만 보고 있었다.</strong> 새는 자리는
 * {@code integrationTest} 였고, 그래서 단위 테스트는 초록인 채로 {@code SimDriverIT} 이 컨텍스트를
 * 띄우다 죽었다. 대조가 있어도 <em>보는 자리가 대상보다 좁으면</em> 같은 일이 난다
 * (DESIGN.md §13 규칙 3).
 *
 * <p>그래서 {@link #assertJpaAbsent()} 는 여기와 {@code MessagingDependencyIT} 양쪽에서 불린다.
 * 어설션을 복사하지 않는 이유는 그것이 또 하나의 「서로를 비추는 목록」이 되기 때문이다.
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
    void JPA_는_test_클래스패스에_없다() {
        assertJpaAbsent();
    }

    /**
     * JPA 가 이 클래스로더에 없다 — {@code integrationTest} 쪽에서도 같은 것을 확인한다
     * ({@code MessagingDependencyIT}). 클래스패스마다 부르는 쪽이 다르고 어설션은 하나다.
     */
    static void assertJpaAbsent() {
        assertThatThrownBy(() -> Class.forName("jakarta.persistence.EntityManagerFactory"))
                .as("sim-runner 는 DB 가 없다. JPA 가 들어오면 DataSourceAutoConfiguration 이 기동을 막는다")
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.zaxxer.hikari.HikariDataSource"))
                .as("커넥션 풀이 따라 들어오면 데이터소스 자동설정이 함께 켜진다")
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void Kafka_는_test_클래스패스에_있다() {
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
