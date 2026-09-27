package com.dawnline.order.adapter.out.redis;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * 기동 뒤 Redis 연결을 미리 연다 — best-effort 이고 레디니스 조건이 아니다
 * ([ADR-069](docs/adr/ADR-069-redis-connect-budget-is-not-the-command-budget.md) 결정 2, DESIGN.md §8.6).
 *
 * <p>Lettuce 의 공유 연결은 게으르다. 아무도 열지 않으면 첫 연결은 <strong>첫 주문이 핫패스에서</strong> 열고, 콜드 JVM 에 요청이
 * 몰리는 그 순간이 연결 수립이 가장 느린 순간이다. 여기서 먼저 열면 그 일이 핫패스 밖으로 나간다.
 *
 * <p>실패해도 기동은 그대로다. 폴백이 있는 의존성을 레디니스에 넣으면 Redis 장애가 곧 트래픽 차단이다(ADR-016) — 선연결이 실패하면
 * 첫 명령이 다시 연결하고, 그 예산은 연결 예산이다({@code RedisTimeoutConfig}). 기동 스레드를 붙잡지 않으려고 별도 스레드에서 연다.
 */
public class RedisConnectionWarmup implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(RedisConnectionWarmup.class);

    private final RedisConnectionFactory connectionFactory;

    /**
     * @param connectionFactory Boot 의 연결 팩토리 — 공유 연결을 여기서 연다
     */
    public RedisConnectionWarmup(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        Thread.ofVirtual().name("redis-warmup").start(this::connect);
    }

    /**
     * 공유 연결을 열고 {@code PING} 한다.
     *
     * @return 연결됐으면 {@code true}. 실패는 WARN 한 줄이고 던지지 않는다
     */
    public boolean connect() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.ping();
            log.info("Redis 연결을 미리 열었다");
            return true;
        } catch (RuntimeException failed) {
            // 가장 안쪽 원인을 남긴다 — 겉의 「Unable to connect」 는 예산 초과와 거부를 가르지 않는다. 첫 명령이 다시 연다.
            log.warn("Redis 선연결에 실패했다 — 첫 명령이 다시 연다: {}",
                    NestedExceptionUtils.getMostSpecificCause(failed).toString());
            return false;
        }
    }
}
