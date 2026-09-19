package com.dawnline.tracking.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dawnline.tracking.application.TrackingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * at-risk 쿨다운 — <strong>창을 못 연 것과 Redis 가 죽은 것은 다르다</strong>.
 *
 * <p>전자는 「방금 알렸다」는 뜻이라 건너뛰는 것이 맞다. 후자에 건너뛰면 Redis 장애가 곧
 * <em>위험 감지 중단</em>이 되고, 그것은 §7.2 가 이 키에 「중복 허용」이라고 적어 둔 것의
 * 정반대다. 알림이 시끄러워지는 것보다 위험을 놓치는 쪽이 훨씬 나쁘다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("RedisAtRiskCooldown — Redis 장애는 침묵이 아니라 발행이다")
class RedisAtRiskCooldownTest {

    private static final UUID ROUTE = UUID.randomUUID();
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private ValueOperations<String, String> values;
    private SimpleMeterRegistry registry;
    private RedisAtRiskCooldown cooldown;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        registry = new SimpleMeterRegistry();
        cooldown = new RedisAtRiskCooldown(redis, new TrackingMetrics(registry), WINDOW);
    }

    @Test
    void 비어_있으면_창을_열고_TTL_을_건다() {
        // TTL 이 없으면 한 번 위험했던 라우트는 다시는 알리지 못한다 — 해제하는 코드가 없다.
        when(values.setIfAbsent(eq(key()), anyString(), eq(WINDOW))).thenReturn(true);

        assertThat(cooldown.tryStart(ROUTE)).isTrue();
    }

    @Test
    void 창이_열려_있으면_건너뛴다() {
        when(values.setIfAbsent(eq(key()), anyString(), eq(WINDOW))).thenReturn(false);

        assertThat(cooldown.tryStart(ROUTE)).isFalse();
        assertThat(registry.find(TrackingMetrics.COOLDOWN_BYPASSED).counter().count())
                .as("정상적인 건너뜀은 폴백이 아니다")
                .isZero();
    }

    @Test
    void Redis_가_죽으면_쿨다운_없이_발행한다() {
        when(values.setIfAbsent(eq(key()), anyString(), eq(WINDOW)))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cooldown.tryStart(ROUTE))
                .as("건너뛰면 Redis 장애가 곧 위험 감지 중단이 된다 (§7.2)")
                .isTrue();
        assertThat(registry.find(TrackingMetrics.COOLDOWN_BYPASSED).counter().count())
                .as("폴백은 조용히 일어나면 안 된다 — 「알림이 늘었다」가 Redis 장애일 수 있다")
                .isEqualTo(1.0);
    }

    @Test
    void 널_응답도_창이_열린_것으로_읽지_않는다() {
        // 템플릿이 null 을 돌려주는 경우가 있다. Boolean.TRUE.equals 가 그것을 false 로 만든다.
        when(values.setIfAbsent(eq(key()), anyString(), eq(WINDOW))).thenReturn(null);

        assertThat(cooldown.tryStart(ROUTE)).isFalse();
    }

    @Test
    void 키는_설계서의_형태다() {
        // §7.2 — route:{id}:atrisk:cooldown. 형태가 갈리면 운영자가 찾지 못한다.
        assertThat(RedisAtRiskCooldown.keyOf(ROUTE)).isEqualTo("route:" + ROUTE + ":atrisk:cooldown");
    }

    private static String key() {
        return RedisAtRiskCooldown.keyOf(ROUTE);
    }
}
