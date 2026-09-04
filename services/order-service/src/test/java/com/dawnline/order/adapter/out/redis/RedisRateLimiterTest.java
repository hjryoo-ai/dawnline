package com.dawnline.order.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dawnline.common.Ids;
import com.dawnline.order.OrderMetrics;
import com.dawnline.order.application.port.out.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 레이트 리밋 판정의 번역과 폴백 (DESIGN.md §7.2, §8.1).
 *
 * <p>Lua 스크립트 자체는 {@code RateLimitIT} 가 실물 Redis 로 본다. 여기서 보는 것은
 * <em>스크립트 결과를 무엇으로 읽는가</em>와 <strong>Redis 가 아플 때 어떻게 되는가</strong>다.
 * 후자가 이 클래스의 존재 이유다 — fail-open 은 조용히 사라지면 안 되는 종류의 폴백이다(§10).
 */
@DisplayName("RedisRateLimiter — 판정과 fail-open")
class RedisRateLimiterTest {

    private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");
    private static final UUID CUSTOMER = Ids.newId();

    private StringRedisTemplate redis;
    private RedisOutageGate gate;
    private MeterRegistry meters;
    private RedisRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        gate = new RedisOutageGate(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(10));
        meters = new SimpleMeterRegistry();
        limiter = new RedisRateLimiter(redis, gate, Clock.fixed(NOW, ZoneOffset.UTC), meters, 60, 1, 60);
    }

    @SuppressWarnings("unchecked")
    private void scriptReturns(List<Long> result) {
        when(redis.execute(any(RedisScript.class), any(List.class), any(Object[].class))).thenReturn(result);
    }

    @SuppressWarnings("unchecked")
    private void scriptThrows() {
        when(redis.execute(any(RedisScript.class), any(List.class), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("연결 실패"));
    }

    private double count(String outcome) {
        var counter = meters.find(OrderMetrics.RATE_LIMIT_DECISIONS)
                .tag(OrderMetrics.TAG_OUTCOME, outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void 토큰이_있으면_허용이다() {
        scriptReturns(List.of(1L, 0L));

        RateLimiter.Decision decision = limiter.tryAcquire(CUSTOMER);

        assertThat(decision.outcome()).isEqualTo(RateLimiter.Outcome.ALLOWED);
        assertThat(decision.isAllowed()).isTrue();
        assertThat(count("allowed")).isEqualTo(1);
    }

    @Test
    void 토큰이_없으면_제한이고_대기_시간을_준다() {
        scriptReturns(List.of(0L, 3L));

        RateLimiter.Decision decision = limiter.tryAcquire(CUSTOMER);

        assertThat(decision.outcome()).isEqualTo(RateLimiter.Outcome.LIMITED);
        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(3);
        assertThat(count("limited")).isEqualTo(1);
    }

    @Test
    void 대기_시간은_최소_1초다() {
        // Retry-After: 0 은 "지금 다시 보내라" 라 의미가 없다.
        scriptReturns(List.of(0L, 0L));

        assertThat(limiter.tryAcquire(CUSTOMER).retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void Redis_가_죽으면_통과시키고_그_사실을_센다() {
        // 인증이 없는 API 에서 이것은 유일한 남용 방지 수단이다(§10). 조용히 꺼지면 안 된다.
        scriptThrows();

        RateLimiter.Decision decision = limiter.tryAcquire(CUSTOMER);

        assertThat(decision.outcome()).isEqualTo(RateLimiter.Outcome.BYPASSED);
        assertThat(decision.isAllowed()).as("통과시킨다 — 주문을 막지 않는다").isTrue();
        assertThat(count("bypassed")).isEqualTo(1);
    }

    @Test
    void 실패하면_차단기를_열고_그_뒤로는_Redis_를_부르지도_않는다() {
        // 이것이 SLO 보호의 핵심이다(§8.1). 타임아웃만 있으면 500 rps 에서 초당 500번씩 기다린다.
        scriptThrows();
        limiter.tryAcquire(CUSTOMER);
        assertThat(gate.isBypassing()).isTrue();

        StringRedisTemplate untouched = mock(StringRedisTemplate.class);
        RedisRateLimiter afterOutage = new RedisRateLimiter(untouched, gate,
                Clock.fixed(NOW, ZoneOffset.UTC), meters, 60, 1, 60);

        assertThat(afterOutage.tryAcquire(CUSTOMER).outcome()).isEqualTo(RateLimiter.Outcome.BYPASSED);
        verifyNoInteractions(untouched);
    }

    @Test
    void 스크립트가_이상한_값을_주면_통과시킨다() {
        // 판정기의 버그로 주문 접수를 막는 것보다, 남용 방지가 잠깐 꺼지는 쪽이 낫다.
        // 그리고 그 사실은 bypassed 로 보인다.
        scriptReturns(List.of(1L));

        assertThat(limiter.tryAcquire(CUSTOMER).outcome()).isEqualTo(RateLimiter.Outcome.BYPASSED);
        assertThat(count("bypassed")).isEqualTo(1);
    }

    @Test
    void 결과가_null_이어도_통과시킨다() {
        scriptReturns(null);

        assertThat(limiter.tryAcquire(CUSTOMER).outcome()).isEqualTo(RateLimiter.Outcome.BYPASSED);
    }

    @Test
    void 잘못된_설정은_생성에서_거부한다() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThatThrownBy(() -> new RedisRateLimiter(redis, gate, clock, meters, 0, 1, 60))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("capacity");
        assertThatThrownBy(() -> new RedisRateLimiter(redis, gate, clock, meters, 60, 0, 60))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("refillPerSecond");
        assertThatThrownBy(() -> new RedisRateLimiter(redis, gate, clock, meters, 60, 1, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ttlSeconds");
        assertThatThrownBy(() -> new RedisRateLimiter(null, gate, clock, meters, 60, 1, 60))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> limiter.tryAcquire(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 키는_7_2_형식이고_시각은_주입된_시계에서_온다() {
        // 시각을 인자로 넘기는 이유: Lua 가 redis.call('TIME') 을 쓰면 리필을 테스트로 재현할 수
        // 없다 (불변규칙 12).
        scriptReturns(List.of(1L, 0L));

        limiter.tryAcquire(CUSTOMER);

        org.mockito.ArgumentCaptor<List<String>> keys = org.mockito.ArgumentCaptor.captor();
        org.mockito.ArgumentCaptor<Object[]> args = org.mockito.ArgumentCaptor.captor();
        org.mockito.Mockito.verify(redis).execute(any(RedisScript.class), keys.capture(), args.capture());

        assertThat(keys.getValue()).containsExactly("rl:customer:" + CUSTOMER);
        assertThat(args.getValue()).contains(Long.toString(NOW.toEpochMilli()));
    }
}
