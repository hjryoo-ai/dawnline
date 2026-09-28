package com.dawnline.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.dawnline.common.net.DelayingTcpProxy;
import com.dawnline.order.adapter.out.redis.RedisConnectionWarmup;
import com.dawnline.order.config.OrderProperties;
import com.dawnline.order.config.RedisTimeoutConfig;
import com.redis.testcontainers.RedisContainer;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 의 연결 예산은 명령 예산이 아니다 — 실제 Redis 8 과 응답을 늦추는 프록시
 * ([ADR-069](docs/adr/ADR-069-redis-connect-budget-is-not-the-command-budget.md)).
 *
 * <p>컨텍스트는 Boot 의 Redis 자동설정과 {@link RedisTimeoutConfig} 둘만 띄운다 — 보려는 것이 <strong>Boot 가 두 커스터마이저를 실제로
 * 적용한 팩토리</strong>이기 때문이다. 팩토리를 손으로 만들면 설정이 아니라 테스트 코드를 검사하게 된다.
 *
 * <p>느림은 프록시가 만든다. 새 연결의 첫 응답만 늦추면 핸드셰이크(HELLO)가 느린 연결 수립이고, 연결 뒤에 모든 응답을 늦추면
 * 명령이 느리다.
 * 늦추는 200 ms 는 명령 예산 50 ms 의 네 배이고 연결 예산 2초의 10분의 1이다 — 어느 예산이 걸렸는지가 결과에 그대로 드러나는 값이다.
 */
@DisplayName("Redis 연결 예산 · 명령 예산 · 선연결 (ADR-069)")
class RedisConnectTimeoutIT {

    /** deploy/compose/.env.example 의 {@code REDIS_IMAGE} 와 같은 태그. */
    private static final String REDIS_IMAGE = "redis:8.8.2";

    private static final Duration SLOW = Duration.ofMillis(200);

    private static RedisContainer redis;

    private DelayingTcpProxy proxy;

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OrderProperties.class)
    static class Properties {
    }

    @BeforeAll
    static void startRedis() {
        redis = new RedisContainer(REDIS_IMAGE);
        redis.start();
    }

    @AfterAll
    static void stopRedis() {
        redis.stop();
    }

    @BeforeEach
    void openProxy() {
        proxy = DelayingTcpProxy.to(redis.getHost(), redis.getFirstMappedPort());
    }

    @AfterEach
    void closeProxy() {
        proxy.close();
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
                .withUserConfiguration(Properties.class, RedisTimeoutConfig.class)
                .withPropertyValues(
                        "spring.data.redis.host=" + proxy.host(),
                        "spring.data.redis.port=" + proxy.port());
    }

    @Test
    void 핸드셰이크가_명령_예산보다_느려도_선연결은_연결된다() {
        runner().run(context -> {
            proxy.delayFirstReply(SLOW);
            long started = System.nanoTime();

            boolean connected = context.getBean(RedisConnectionWarmup.class).connect();

            Duration took = Duration.ofNanos(System.nanoTime() - started);
            assertThat(took).as("전제 — 연결이 실제로 명령 예산보다 느렸다").isGreaterThanOrEqualTo(SLOW);
            assertThat(connected).as("연결 예산(2초) 안이다").isTrue();
        });
    }

    @Test
    void 연결된_뒤의_명령은_명령_예산에서_끊긴다() {
        runner().run(context -> {
            StringRedisTemplate template = context.getBean(StringRedisTemplate.class);
            assertThat(context.getBean(RedisConnectionWarmup.class).connect()).as("전제 — 지연 없이 연결했다").isTrue();
            proxy.delayReplies(SLOW);
            long started = System.nanoTime();

            Throwable failure = catchThrowable(() -> template.opsForValue().get("adr-069"));

            Duration took = Duration.ofNanos(System.nanoTime() - started);
            assertThat(failure).isInstanceOf(QueryTimeoutException.class);
            assertThat(took).as("명령 예산 50 ms 에서 끊긴다 — 연결 예산 2초가 아니다").isLessThan(SLOW);
        });
    }

    @Test
    void Redis_가_없으면_선연결은_실패를_말하고_던지지_않는다() {
        runner().run(context -> {
            proxy.refuseConnections();
            assertThat(answers(proxy)).as("전제 — 프록시가 연결을 받자마자 끊는다(포트는 쥐고 있다)").isFalse();

            assertThat(context.getBean(RedisConnectionWarmup.class).connect())
                    .as("레디니스 조건이 아니다 — 기동을 막지 않는다(ADR-016)").isFalse();
        });
    }

    @Test
    void 선연결한_연결을_명령이_그대로_쓴다() {
        runner().run(context -> {
            assertThat(context.getBean(RedisConnectionWarmup.class).connect()).isTrue();
            int afterWarmup = proxy.acceptedConnections();
            StringRedisTemplate template = context.getBean(StringRedisTemplate.class);

            template.opsForValue().set("adr-069", "warm");
            assertThat(template.opsForValue().get("adr-069")).isEqualTo("warm");

            assertThat(afterWarmup).as("전제 — 선연결이 연결을 열었다").isEqualTo(1);
            assertThat(proxy.acceptedConnections()).as("명령이 새 연결을 열지 않았다").isEqualTo(afterWarmup);
        });
    }

    /** 연결해서 한 줄을 보내고, 무엇이든 돌아오면 참이다 — 받자마자 끊는 프록시는 EOF 를 준다. */
    private static boolean answers(DelayingTcpProxy proxy) throws IOException {
        try (Socket socket = new Socket(proxy.host(), proxy.port())) {
            socket.setSoTimeout(2_000);
            socket.getOutputStream().write("PING\r\n".getBytes(StandardCharsets.US_ASCII));
            return socket.getInputStream().read() >= 0;
        } catch (SocketException reset) {
            return false;
        }
    }
}
