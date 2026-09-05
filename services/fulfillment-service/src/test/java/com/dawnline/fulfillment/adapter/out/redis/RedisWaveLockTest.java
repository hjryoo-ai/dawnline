package com.dawnline.fulfillment.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dawnline.fulfillment.application.port.out.WaveLock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 웨이브 마감 락 — <strong>락을 못 얻은 것과 Redis 가 죽은 것은 다르다</strong>.
 *
 * <p>전자는 다른 인스턴스가 처리 중이라는 뜻이라 스킵이 맞다. 후자에 스킵하면 Redis 장애가 곧
 * 마감 중단이 되고, 마감이 멈추면 웨이브가 영원히 열려 있어 계획이 시작되지 않는다. 중복 마감을
 * 실제로 막는 것은 {@code FOR UPDATE} 와 상태 전이이므로(불변규칙 7) 여기서는 진행한다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("RedisWaveLock — Redis 장애는 스킵이 아니라 진행이다")
class RedisWaveLockTest {

    private static final UUID WAVE_ID = UUID.randomUUID();
    private static final String KEY = RedisWaveLock.KEY_PREFIX + WAVE_ID;

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SimpleMeterRegistry registry;
    private RedisWaveLock lock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        registry = new SimpleMeterRegistry();
        lock = new RedisWaveLock(redis, new GeoMetrics(registry), Duration.ofSeconds(60));
    }

    @Test
    void 비어_있으면_잡고_TTL_을_건다() {
        // TTL 이 없으면 프로세스가 죽었을 때 그 웨이브가 영원히 잠긴다.
        when(values.setIfAbsent(eq(KEY), anyString(), eq(Duration.ofSeconds(60)))).thenReturn(true);

        assertThat(lock.tryLock(WAVE_ID)).isPresent();
        verify(values).setIfAbsent(eq(KEY), anyString(), eq(Duration.ofSeconds(60)));
    }

    @Test
    void 다른_인스턴스가_쥐고_있으면_비어_있다() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThat(lock.tryLock(WAVE_ID)).isEmpty();
    }

    @Test
    void Redis_가_죽으면_락_없이_진행한다() {
        // 여기서 스킵하면 Redis 장애가 곧 마감 중단이다. 그것은 폴백이 아니다.
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("죽었다"));

        assertThat(lock.tryLock(WAVE_ID)).isPresent();
        assertThat(counter("bypassed")).isEqualTo(1);
    }

    @Test
    void 죽은_Redis_에서_받은_핸들은_닫아도_안전하다() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("죽었다"));

        WaveLock.Guard guard = lock.tryLock(WAVE_ID).orElseThrow();

        assertThat(guard).isNotNull();
        guard.close();
    }

    @Test
    void 해제는_토큰을_비교하는_스크립트로_한다() {
        // DEL 만 하면 TTL 만료 뒤 남이 잡은 락을 지울 수 있다. 비교와 삭제가 원자적이어야 한다.
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        lock.tryLock(WAVE_ID).orElseThrow().close();

        verify(redis).execute(anyScript(), eq(List.of(KEY)), anyString());
    }

    @Test
    void 해제_실패는_밖으로_나가지_않는다() {
        // 여기서 예외를 내면 마감 자체가 실패한 것처럼 보인다. TTL 이 결국 정리한다.
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redis.execute(anyScript(), anyKeys(), any()))
                .thenThrow(new RedisConnectionFailureException("죽었다"));

        Optional<WaveLock.Guard> guard = lock.tryLock(WAVE_ID);

        assertThat(guard).isPresent();
        guard.orElseThrow().close();
    }

    @Test
    void TTL_은_양수여야_한다() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new RedisWaveLock(redis, new GeoMetrics(registry), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 제네릭 매처. raw 타입으로 쓰면 -Werror 에 걸린다. */
    @SuppressWarnings("unchecked")
    private static RedisScript<Long> anyScript() {
        return any(RedisScript.class);
    }

    @SuppressWarnings("unchecked")
    private static List<String> anyKeys() {
        return any(List.class);
    }

    private double counter(String outcome) {
        var found = registry.find(GeoMetrics.LOOKUPS_COUNTER)
                .tag("index", "wave_lock").tag("outcome", outcome).counter();
        return found == null ? 0 : found.count();
    }
}
