package com.dawnline.order.config;

import com.dawnline.order.adapter.out.redis.RedisIdempotencyCache;
import com.dawnline.order.adapter.out.redis.RedisOutageGate;
import com.dawnline.order.adapter.out.redis.RedisRateLimiter;
import com.dawnline.order.application.port.out.IdempotencyCache;
import com.dawnline.order.application.port.out.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 어댑터 배선 (DESIGN.md §7.2).
 *
 * <p>연결 팩토리와 {@link StringRedisTemplate} 은 Boot 가 자동설정한다. 여기서는 포트 구현과
 * <strong>지연 예산</strong>을 잇는다.
 *
 * <p>이 빈들이 생긴다고 해서 기동이 Redis 에 묶이지는 않는다 — Lettuce 는 연결을 지연시키고,
 * 어댑터들은 실패를 폴백으로 바꾼다. Redis 가 꺼져 있어도 주문 접수는 계속된다(불변규칙 7, §8.4).
 */
@Configuration(proxyBeanMethods = false)
public class RedisConfig {

    /**
     * 명령 타임아웃을 짧게 잡는다 (§7.2, §8.1).
     *
     * <h2>왜 별도 연결 팩토리를 만들지 않는가</h2>
     * Boot 의 Lettuce 팩토리는 {@code @ConditionalOnMissingBean(RedisConnectionFactory.class)} 다.
     * 타임아웃만 다른 두 번째 팩토리를 빈으로 올리면 <strong>Boot 의 기본 팩토리가 조용히 사라진다</strong>
     * — 조건이 "그 타입의 빈이 이미 있는가" 만 보기 때문이다(바이트코드로 확인). 커스터마이저는
     * Boot 가 제공하는 확장점이고, 속성 적용 <em>뒤에</em> 실행되므로 여기서 준 값이 이긴다.
     *
     * <h2>왜 두 경로에 같은 타임아웃인가</h2>
     * order-service 의 Redis 사용은 멱등 캐시와 레이트 리밋 둘뿐이고, 둘 다 {@code POST /orders}
     * 핫패스에 있으며 둘 다 실패해도 안전하다. 한쪽만 짧게 잡으면 나머지 한쪽이 같은 SLO 구멍으로
     * 남는다 — 실제로 멱등 캐시가 그 상태였다.
     *
     * @param properties {@code dawnline.order.redis.*}
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer redisCommandTimeoutCustomizer(OrderProperties properties) {
        return builder -> builder.commandTimeout(properties.redis().commandTimeout());
    }

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
