package com.dawnline.messaging.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dawnline.messaging.outbox.RelayLeadership;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 릴레이 리더 락 — <strong>Redis 장애는 fail-closed 다</strong> (ADR-027).
 *
 * <p>{@code RedisWaveLock} 과 반대인 이유는 실패했을 때 잃는 것이 다르기 때문이다. 마감은 시각을
 * 놓치면 돌아오지 않고 중복 마감은 DB 가 막는다(fail-open). 여기서 잃는 것은 키 단위 순서이고
 * DB 에는 그것을 지킬 수단이 없다 — 대신 멈춰서 잃는 것은 지연뿐이고, 그것은 돌아온다.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@DisplayName("RedisRelayLeadership — 모를 때는 멈춘다")
class RedisRelayLeadershipTest {

    private StringRedisTemplate redis;
    private RedisRelayLeadership leadership;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        leadership = new RedisRelayLeadership(redis, "order-service", Duration.ofSeconds(30));
    }

    @Test
    void 키를_잡으면_리더다() {
        scriptReturns(1L);

        assertThat(leadership.lead()).isEqualTo(RelayLeadership.State.LEADER);
    }

    @Test
    void 남이_잡고_있으면_팔로워다() {
        scriptReturns(0L);

        assertThat(leadership.lead()).isEqualTo(RelayLeadership.State.FOLLOWER);
    }

    @Test
    void Redis_가_죽으면_팔로워가_아니라_판정불가다() {
        // 둘을 합치면 대시보드에서 "다른 인스턴스가 일하는 중" 과 "아무도 판정하지 못한다" 를
        // 구별할 수 없다. 발행을 멈추는 결정은 같지만 봐야 할 곳이 정반대다.
        scriptThrows();

        assertThat(leadership.lead()).isEqualTo(RelayLeadership.State.UNKNOWN);
    }

    @Test
    void 스크립트_결과가_null_이면_리더가_아니다() {
        // 파이프라인·트랜잭션 안에서 Redis 템플릿은 null 을 돌려준다. 리더로 읽으면 안 된다.
        scriptReturns(null);

        assertThat(leadership.lead()).isEqualTo(RelayLeadership.State.FOLLOWER);
    }

    @Test
    void 토큰은_인스턴스_수명_동안_고정이다() {
        // 매번 새로 만들면 갱신이 늘 실패해 자기 자신에게서 리더십을 빼앗는다.
        scriptReturns(1L);

        leadership.lead();
        leadership.lead();

        java.util.List<Object[]> calls = capturedArgs();
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0)[0]).isEqualTo(calls.get(1)[0]);
    }

    @Test
    void ttl_을_밀리초로_넘긴다() {
        // Lua 의 PX 인자다. 초로 넘기면 30ms 짜리 리더십이 된다.
        scriptReturns(1L);

        leadership.lead();

        assertThat(capturedArgs().get(0)[1]).isEqualTo("30000");
    }

    @Test
    void 키는_서비스마다_다르다() {
        // 한 Redis 를 여러 서비스가 쓴다. 키가 같으면 order 릴레이가 dispatch 릴레이를 멈춘다.
        assertThat(leadership.key()).isEqualTo("lock:relay:order-service");
        assertThat(new RedisRelayLeadership(redis, "dispatch-service", Duration.ofSeconds(30)).key())
                .isEqualTo("lock:relay:dispatch-service");
    }

    @Test
    void stepDown_은_실패해도_던지지_않는다() {
        // 종료 경로다. 여기서 던지면 정상 종료가 실패로 보인다. TTL 이 결국 정리한다.
        scriptThrows();

        assertThatNoException().isThrownBy(leadership::stepDown);
    }

    @Test
    void ttl_이_0이면_생성자에서_예외() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RedisRelayLeadership(redis, "order-service", Duration.ZERO));
    }

    @SuppressWarnings("unchecked")
    private void scriptReturns(@Nullable Long result) {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(result);
    }

    @SuppressWarnings("unchecked")
    private void scriptThrows() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("연결 실패"));
    }

    @SuppressWarnings("unchecked")
    private java.util.List<Object[]> capturedArgs() {
        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(redis, atLeastOnce()).execute(any(RedisScript.class), anyList(), captor.capture());
        return captor.getAllValues();
    }
}
