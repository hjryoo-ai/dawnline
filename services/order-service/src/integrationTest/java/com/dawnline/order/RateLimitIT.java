package com.dawnline.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.common.Ids;
import com.dawnline.order.OrderMetrics;
import com.dawnline.order.adapter.out.redis.RedisOutageGate;
import com.dawnline.order.adapter.out.redis.RedisRateLimiter;
import com.dawnline.order.application.port.out.RateLimiter;
import com.redis.testcontainers.RedisContainer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Lua 토큰 버킷 (DESIGN.md §7.2 `rl:customer:{id}`) — 실제 Redis 8.
 *
 * <p>스프링 컨텍스트를 띄우지 않는다. 여기서 보려는 것은 <strong>스크립트가 Redis 안에서 실제로
 * 어떻게 도는가</strong>이고, 그러려면 시계를 마음대로 앞으로 돌릴 수 있어야 한다. 컨텍스트의
 * {@code Clock} 빈으로는 리필을 검사할 수 없어서 어댑터를 직접 만든다.
 *
 * <p>리필 검사가 이 테스트의 핵심이다. 용량과 429 만 보면 "토큰이 다시 차는가" 를 확인하지 못하고,
 * 그러면 한 번 소진된 고객이 영원히 막히는 버그가 통과한다.
 */
@DisplayName("RateLimitIT — Lua 토큰 버킷")
class RateLimitIT {

    /** deploy/compose/.env.example 의 {@code REDIS_IMAGE} 와 같은 태그. */
    private static final String REDIS_IMAGE = "redis:8.8.2";

    private static final Instant START = Instant.parse("2026-09-04T00:00:00Z");
    private static final int CAPACITY = 5;
    private static final int REFILL_PER_SECOND = 1;
    private static final int TTL_SECONDS = 60;

    private static RedisContainer redis;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;

    /** 앞으로 돌릴 수 있는 시계 — 리필을 기다리지 않고 검사한다 (불변규칙 12). */
    private static final class MovableClock extends Clock {
        private Instant now = START;

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovableClock clock = new MovableClock();

    @BeforeAll
    static void startRedis() {
        redis = new RedisContainer(REDIS_IMAGE);
        redis.start();
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getRedisHost(), redis.getRedisPort()));
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        factory.destroy();
        redis.stop();
    }

    private RedisRateLimiter limiter() {
        return new RedisRateLimiter(template,
                new RedisOutageGate(clock, Duration.ofSeconds(10)),
                clock, new SimpleMeterRegistry(), CAPACITY, REFILL_PER_SECOND, TTL_SECONDS);
    }

    @Test
    void 용량만큼_허용하고_그_다음부터_막는다() {
        RedisRateLimiter limiter = limiter();
        UUID customer = Ids.newId();

        for (int i = 1; i <= CAPACITY; i++) {
            assertThat(limiter.tryAcquire(customer).outcome())
                    .as("%d번째 요청", i)
                    .isEqualTo(RateLimiter.Outcome.ALLOWED);
        }

        RateLimiter.Decision blocked = limiter.tryAcquire(customer);
        assertThat(blocked.outcome()).isEqualTo(RateLimiter.Outcome.LIMITED);
        assertThat(blocked.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void 시간이_지나면_토큰이_다시_찬다() {
        // 이것이 없으면 한 번 소진된 고객이 영원히 막히는 버그가 통과한다.
        RedisRateLimiter limiter = limiter();
        UUID customer = Ids.newId();
        for (int i = 0; i < CAPACITY; i++) {
            limiter.tryAcquire(customer);
        }
        assertThat(limiter.tryAcquire(customer).outcome()).isEqualTo(RateLimiter.Outcome.LIMITED);

        clock.advance(Duration.ofSeconds(2));

        assertThat(limiter.tryAcquire(customer).outcome()).isEqualTo(RateLimiter.Outcome.ALLOWED);
        assertThat(limiter.tryAcquire(customer).outcome()).isEqualTo(RateLimiter.Outcome.ALLOWED);
        assertThat(limiter.tryAcquire(customer).outcome())
                .as("2초에 2개만 찬다")
                .isEqualTo(RateLimiter.Outcome.LIMITED);
    }

    @Test
    void 리필은_용량을_넘지_않는다() {
        // 오래 쉰 고객이 무한한 버스트를 얻으면 레이트 리밋이 아니다.
        RedisRateLimiter limiter = limiter();
        UUID customer = Ids.newId();
        limiter.tryAcquire(customer);

        clock.advance(Duration.ofHours(1));

        for (int i = 1; i <= CAPACITY; i++) {
            assertThat(limiter.tryAcquire(customer).outcome())
                    .as("%d번째", i)
                    .isEqualTo(RateLimiter.Outcome.ALLOWED);
        }
        assertThat(limiter.tryAcquire(customer).outcome()).isEqualTo(RateLimiter.Outcome.LIMITED);
    }

    @Test
    void 고객마다_버킷이_따로다() {
        RedisRateLimiter limiter = limiter();
        UUID heavy = Ids.newId();
        UUID quiet = Ids.newId();
        for (int i = 0; i < CAPACITY + 1; i++) {
            limiter.tryAcquire(heavy);
        }

        assertThat(limiter.tryAcquire(heavy).outcome()).isEqualTo(RateLimiter.Outcome.LIMITED);
        assertThat(limiter.tryAcquire(quiet).outcome()).isEqualTo(RateLimiter.Outcome.ALLOWED);
    }

    @Test
    void 처음_보는_고객은_가득_찬_버킷으로_시작한다() {
        // TTL 로 버킷이 사라진 뒤에도 같다. 그 시간이면 어차피 다 찼을 값이라 결과가 같다.
        RedisRateLimiter limiter = limiter();

        assertThat(limiter.tryAcquire(Ids.newId()).outcome()).isEqualTo(RateLimiter.Outcome.ALLOWED);
    }

    @Test
    void 버킷에_TTL_이_걸린다() {
        // TTL 이 없으면 한 번이라도 주문한 고객의 키가 영원히 남는다.
        RedisRateLimiter limiter = limiter();
        UUID customer = Ids.newId();

        limiter.tryAcquire(customer);

        Long ttl = template.getExpire("rl:customer:" + customer);
        assertThat(ttl).isNotNull().isBetween(1L, (long) TTL_SECONDS);
    }

    @Test
    void 시계가_뒤로_가도_토큰이_줄지_않는다() {
        // NTP 보정으로 시계가 뒤로 갈 수 있다. elapsed 를 음수로 두면 토큰이 깎인다.
        RedisRateLimiter limiter = limiter();
        UUID customer = Ids.newId();
        limiter.tryAcquire(customer);

        clock.advance(Duration.ofSeconds(-30));

        for (int i = 1; i < CAPACITY; i++) {
            assertThat(limiter.tryAcquire(customer).outcome())
                    .as("%d번째", i)
                    .isEqualTo(RateLimiter.Outcome.ALLOWED);
        }
    }

    @Test
    void 판정마다_메트릭이_남는다() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RedisRateLimiter limiter = new RedisRateLimiter(template,
                new RedisOutageGate(clock, Duration.ofSeconds(10)),
                clock, meters, 1, REFILL_PER_SECOND, TTL_SECONDS);
        UUID customer = Ids.newId();

        limiter.tryAcquire(customer);
        limiter.tryAcquire(customer);

        assertThat(meters.find(OrderMetrics.RATE_LIMIT_DECISIONS)
                .tag(OrderMetrics.TAG_OUTCOME, "allowed").counter().count()).isEqualTo(1);
        assertThat(meters.find(OrderMetrics.RATE_LIMIT_DECISIONS)
                .tag(OrderMetrics.TAG_OUTCOME, "limited").counter().count()).isEqualTo(1);
    }
}
