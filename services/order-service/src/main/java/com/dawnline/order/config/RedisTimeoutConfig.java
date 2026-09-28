package com.dawnline.order.config;

import com.dawnline.order.adapter.out.redis.RedisConnectionWarmup;
import io.lettuce.core.TimeoutOptions;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Redis 의 두 예산과 선연결 (§7.2, [ADR-069](docs/adr/ADR-069-redis-connect-budget-is-not-the-command-budget.md)).
 *
 * <h2>예산이 둘이다</h2>
 * 연결은 「서버에 닿는가」(초)이고 명령은 「핫패스가 얼마까지 기다리는가」(ms)다. Spring 은 {@code commandTimeout} 을 Lettuce 의
 * {@code RedisURI.timeout} 에 넣고, Lettuce 는 그 값으로 <strong>핸드셰이크를</strong> 기다린다 — 그래서 명령 예산 50 ms 하나만 주면
 * 콜드 경로의 첫 연결이 50 ms 에 끊겼다(7-4 의 창 시작 우회 30–40초, 근거: 관측(재현됨)). 여기서 둘을 가른다: {@code commandTimeout}
 * 자리에는 <em>연결</em> 예산을, 명령 예산은 {@link TimeoutOptions} 로 명령마다 건다. 한 클래스에 나란히 두는 이유는 하나만 고치는
 * 날이 오지 않게 하려는 것이다.
 *
 * <h2>왜 별도 연결 팩토리를 만들지 않는가</h2>
 * Boot 의 Lettuce 팩토리는 {@code @ConditionalOnMissingBean(RedisConnectionFactory.class)} 다. 타임아웃만 다른 두 번째 팩토리를
 * 빈으로 올리면 <strong>Boot 의 기본 팩토리가 조용히 사라진다</strong> — 조건이 "그 타입의 빈이 이미 있는가" 만 보기 때문이다(바이트코드로
 * 확인). 커스터마이저는 Boot 가 제공하는 확장점이고, 속성 적용 <em>뒤에</em> 실행되므로 여기서 준 값이 이긴다.
 *
 * <h2>왜 두 경로에 같은 명령 예산인가</h2>
 * order-service 의 Redis 사용은 멱등 캐시와 레이트 리밋 둘뿐이고, 둘 다 {@code POST /orders} 핫패스에 있으며 둘 다 실패해도 안전하다.
 * 한쪽만 짧게 잡으면 나머지 한쪽이 같은 SLO 구멍으로 남는다 — 실제로 멱등 캐시가 그 상태였다.
 */
@Configuration(proxyBeanMethods = false)
public class RedisTimeoutConfig {

    /**
     * 연결 예산 — {@code RedisURI.timeout} (TCP 연결 + 핸드셰이크).
     *
     * @param properties {@code dawnline.order.redis.connect-timeout-ms}
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer redisConnectTimeoutCustomizer(OrderProperties properties) {
        return builder -> builder.commandTimeout(properties.redis().connectTimeout());
    }

    /**
     * 명령 예산 — 명령마다 고정 타임아웃 (§7.2, §8.1). Boot 는 {@code TimeoutOptions.enabled()}(연결 예산을 명령에도 쓰는 형태)를
     * 이미 켜고 있고, 여기서 그 값을 명령 예산으로 바꾼다.
     *
     * @param properties {@code dawnline.order.redis.command-timeout-ms}
     */
    @Bean
    public LettuceClientOptionsBuilderCustomizer redisCommandTimeoutCustomizer(OrderProperties properties) {
        return builder -> builder.timeoutOptions(TimeoutOptions.enabled(properties.redis().commandTimeout()));
    }

    /**
     * 기동 뒤 best-effort 선연결 — 레디니스 조건이 아니다 (§8.6, ADR-016).
     *
     * @param connectionFactory Boot 의 연결 팩토리
     */
    @Bean
    public RedisConnectionWarmup redisConnectionWarmup(RedisConnectionFactory connectionFactory) {
        return new RedisConnectionWarmup(connectionFactory);
    }
}
