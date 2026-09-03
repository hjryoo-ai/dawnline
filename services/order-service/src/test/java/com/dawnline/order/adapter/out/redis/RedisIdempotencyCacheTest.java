package com.dawnline.order.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dawnline.order.application.port.out.IdempotencyCache;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 멱등 잠금의 Redis 어댑터 (DESIGN.md §5.1 · §7.2, ADR-018).
 *
 * <p>이 클래스에서 가장 중요한 성질은 <strong>어떤 실패도 밖으로 나가지 않는다</strong>는 것이다.
 * Redis 장애가 예외로 올라가면 주문 접수가 멈추고, 그것은 불변규칙 7 과 §8.4 를 동시에 어긴다.
 */
@DisplayName("RedisIdempotencyCache — 잠금은 잡되 장애로 접수를 막지 않는다")
class RedisIdempotencyCacheTest {

    private static final String KEY = "idem-1";
    private static final String REDIS_KEY = "idem:order:idem-1";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RedisIdempotencyCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        cache = new RedisIdempotencyCache(redis);
    }

    @Test
    void 키가_없으면_잠금을_얻고_30초_만료를_건다() {
        // PX 30000 이 ADR-018 의 핵심이다 — 프로세스가 죽어도 30초 뒤 스스로 풀린다.
        when(values.setIfAbsent(eq(REDIS_KEY), eq("IN_PROGRESS"), any(Duration.class))).thenReturn(true);

        assertThat(cache.tryLock(KEY)).isEqualTo(IdempotencyCache.Lock.ACQUIRED);
        verify(values).setIfAbsent(REDIS_KEY, "IN_PROGRESS", Duration.ofSeconds(30));
    }

    @Test
    void 키가_이미_있으면_HELD_다() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThat(cache.tryLock(KEY)).isEqualTo(IdempotencyCache.Lock.HELD);
    }

    @Test
    void 응답이_null_이면_획득으로_보지_않는다() {
        // 파이프라인·트랜잭션 안에서는 null 이 온다. 획득으로 읽으면 잠금 없이 진행하게 된다.
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);

        assertThat(cache.tryLock(KEY)).isEqualTo(IdempotencyCache.Lock.HELD);
    }

    @Test
    void Redis_가_죽어_있으면_UNAVAILABLE_이고_예외를_내지_않는다() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("연결 실패"));

        assertThat(cache.tryLock(KEY)).isEqualTo(IdempotencyCache.Lock.UNAVAILABLE);
    }

    @Test
    void 완료_표시는_DONE_과_24시간_TTL_이다() {
        cache.markDone(KEY);

        verify(values).set(REDIS_KEY, "DONE", Duration.ofHours(24));
    }

    @Test
    void 완료_표시가_실패해도_던지지_않는다() {
        // 여기서 던지면 이미 커밋된 주문이 호출자에게 실패로 보인다.
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("연결 실패"))
                .when(values).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> cache.markDone(KEY)).doesNotThrowAnyException();
    }

    @Test
    void 해제는_키를_지운다() {
        cache.release(KEY);

        verify(redis).delete(REDIS_KEY);
    }

    @Test
    void 해제가_실패해도_던지지_않는다() {
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("연결 실패"));

        assertThatCode(() -> cache.release(KEY)).doesNotThrowAnyException();
    }

    @Test
    void null_키는_거부한다() {
        assertThatCode(() -> cache.tryLock(null)).isInstanceOf(NullPointerException.class);
        assertThatCode(() -> cache.markDone(null)).isInstanceOf(NullPointerException.class);
        assertThatCode(() -> cache.release(null)).isInstanceOf(NullPointerException.class);
        assertThatCode(() -> new RedisIdempotencyCache(null)).isInstanceOf(NullPointerException.class);
    }
}
