package com.dawnline.order.config;

import com.dawnline.order.adapter.out.redis.RedisIdempotencyCache;
import com.dawnline.order.adapter.out.redis.RedisOutageGate;
import com.dawnline.order.adapter.out.redis.RedisRateLimiter;
import com.dawnline.order.application.port.out.IdempotencyCache;
import com.dawnline.order.application.port.out.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 어댑터 배선 (DESIGN.md §7.2).
 *
 * <p>연결 팩토리와 {@link StringRedisTemplate} 은 Boot 가 자동설정한다. 여기서는 포트 구현과
 * <strong>지연 예산</strong>을 잇는다.
 *
 * <p>이 빈들이 생긴다고 해서 기동이 Redis 에 묶이지는 않는다 — 연결은 기동 뒤에 best-effort 로 미리 열고
 * ({@link RedisTimeoutConfig}), 어댑터들은 실패를 폴백으로 바꾼다. Redis 가 꺼져 있어도 주문 접수는 계속된다(불변규칙 7, §8.4).
 */
@Configuration(proxyBeanMethods = false)
public class RedisConfig {

    /**
     * Redis 장애 차단기. 멱등 캐시와 레이트 리밋이 <strong>같은 인스턴스를 공유한다</strong> —
     * 둘이 보는 Redis 가 같으므로, 한쪽이 실패를 감지하면 다른 쪽도 기다릴 이유가 없다.
     *
     * @param clock      시각 출처 (불변규칙 12)
     * @param properties {@code dawnline.order.redis.*}
     */
    @Bean
    public RedisOutageGate redisOutageGate(Clock clock, OrderProperties properties) {
        return new RedisOutageGate(clock, properties.redis().outageBypass());
    }

    /**
     * 멱등 잠금 캐시 (§5.1, ADR-018).
     *
     * @param redis 문자열 전용 템플릿
     * @param gate  Redis 장애 차단기
     */
    @Bean
    public IdempotencyCache idempotencyCache(StringRedisTemplate redis, RedisOutageGate gate) {
        return new RedisIdempotencyCache(redis, gate);
    }

    /**
     * 고객별 레이트 리밋 (§7.2, §8.3).
     *
     * <p>{@code dawnline.order.rate-limit.enabled=false} 로 끌 수 있다. 끄면 무인증 API 의 유일한
     * 남용 방지 수단이 사라지므로(§10), 부하 테스트에서 다른 축을 재고 싶을 때만 쓴다.
     *
     * @param redis      문자열 전용 템플릿
     * @param gate       Redis 장애 차단기
     * @param clock      버킷 리필의 기준 시각
     * @param meters     Micrometer 레지스트리
     * @param properties {@code dawnline.order.rate-limit.*}
     */
    @Bean
    @ConditionalOnProperty(prefix = "dawnline.order.rate-limit", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public RateLimiter rateLimiter(StringRedisTemplate redis, RedisOutageGate gate, Clock clock,
            MeterRegistry meters, OrderProperties properties) {
        OrderProperties.RateLimit rateLimit = properties.rateLimit();
        return new RedisRateLimiter(redis, gate, clock, meters,
                rateLimit.capacity(), rateLimit.refillPerSecond(), rateLimit.ttlSeconds());
    }
}
