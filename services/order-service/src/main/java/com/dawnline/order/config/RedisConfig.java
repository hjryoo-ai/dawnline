package com.dawnline.order.config;

import com.dawnline.order.adapter.out.redis.RedisIdempotencyCache;
import com.dawnline.order.application.port.out.IdempotencyCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 어댑터 배선 (DESIGN.md §7.2).
 *
 * <p>연결 팩토리와 {@link StringRedisTemplate} 은 Boot 가 자동설정한다. 여기서는 포트 구현만 잇는다.
 *
 * <p>이 빈들이 생긴다고 해서 기동이 Redis 에 묶이지는 않는다 — Lettuce 는 연결을 지연시키고,
 * {@link RedisIdempotencyCache} 는 연결 실패를 {@code UNAVAILABLE} 로 바꾼다. Redis 가 꺼져 있어도
 * 주문 접수는 계속된다(불변규칙 7, §8.4).
 */
@Configuration(proxyBeanMethods = false)
public class RedisConfig {

    /**
     * 멱등 잠금 캐시.
     *
     * @param redis 문자열 전용 템플릿
     */
    @Bean
    public IdempotencyCache idempotencyCache(StringRedisTemplate redis) {
        return new RedisIdempotencyCache(redis);
    }
}
