package com.dawnline.messagingtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.dawnline.messaging.outbox.RelayLeadership;
import com.dawnline.messaging.redis.RedisRelayLeadership;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 릴레이 리더 락 — 실제 Redis 로 (§4.4, §7.2, ADR-027).
 *
 * <p>단위 테스트는 <em>판정을 어떻게 읽는지</em>를 본다. 여기서 보는 것은 그 아래다: Lua 두 개가
 * 실제 Redis 에서 원자적으로 도는가, 갱신이 자기 리더십을 연장하는가, 해제가 남의 것을 건드리지
 * 않는가. 이 셋은 목으로는 하나도 검증되지 않는다.
 *
 * <p>스프링 컨텍스트를 띄우지 않는다 — 검증 대상이 배선이 아니라 Redis 와의 상호작용이다.
 * 배선은 이 모듈의 다른 IT 들이 이미 증명한다(리더가 아니면 아무것도 발행되지 않으므로,
 * {@code OutboxRelayIT} 가 통과한다는 것 자체가 리더 락이 붙어 있고 동작한다는 뜻이다).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OutboxLeaderLockIT extends MessagingIntegrationTestBase {

    private static final String SERVICE = "leader-lock-it";
    private static final Duration TTL = Duration.ofSeconds(30);

    private final LettuceConnectionFactory connections = connectionFactory();
    private final StringRedisTemplate redis = template(connections);

    @AfterEach
    void tearDown() {
        redis.delete(RedisRelayLeadership.KEY_PREFIX + SERVICE);
        connections.destroy();
    }

    @Test
    void 두_인스턴스_중_하나만_리더가_된다() {
        // 이 락이 존재하는 이유다. 둘 다 리더면 같은 partition_key 의 행이 두 곳에서 나가고
        // §4.5 의 키 단위 순서가 깨진다 — SKIP LOCKED 로는 막지 못하는 종류의 파손이다.
        RelayLeadership first = leadership();
        RelayLeadership second = leadership();

        assertThat(first.lead()).isEqualTo(RelayLeadership.State.LEADER);
        assertThat(second.lead()).isEqualTo(RelayLeadership.State.FOLLOWER);
    }

    @Test
    void 리더는_반복_호출로_자기_리더십을_갱신한다() {
        // 폴링이 100ms 마다 부른다. 갱신이 아니라 재획득이면 매번 실패해 스스로 리더에서 내려온다.
        RelayLeadership leader = leadership();

        assertThat(leader.lead()).isEqualTo(RelayLeadership.State.LEADER);
        assertThat(leader.lead()).isEqualTo(RelayLeadership.State.LEADER);
        assertThat(leader.lead()).isEqualTo(RelayLeadership.State.LEADER);
    }

    @Test
    void 리더가_내려오면_다음_인스턴스가_이어받는다() {
        // TTL(30초)을 기다리지 않는다. 배포 중 발행이 30초 멈추는 것과 즉시 이어지는 것의 차이다.
        RelayLeadership leaving = leadership();
        RelayLeadership waiting = leadership();
        assertThat(leaving.lead()).isEqualTo(RelayLeadership.State.LEADER);
        assertThat(waiting.lead()).isEqualTo(RelayLeadership.State.FOLLOWER);

        leaving.stepDown();

        assertThat(waiting.lead()).isEqualTo(RelayLeadership.State.LEADER);
    }

    @Test
    void 팔로워의_해제는_리더를_끌어내리지_않는다() {
        // DEL 만 했다면 여기서 리더가 풀린다. 비교와 삭제가 원자적이어야 하는 이유다.
        RelayLeadership leader = leadership();
        RelayLeadership follower = leadership();
        assertThat(leader.lead()).isEqualTo(RelayLeadership.State.LEADER);
        assertThat(follower.lead()).isEqualTo(RelayLeadership.State.FOLLOWER);

        follower.stepDown();

        assertThat(leader.lead())
                .as("팔로워의 stepDown 이 리더의 키를 지우면 안 된다")
                .isEqualTo(RelayLeadership.State.LEADER);
    }

    @Test
    void TTL_이_실제로_걸린다() {
        // TTL 이 없으면 프로세스가 죽은 서비스의 릴레이가 영원히 잠긴다 — 발행이 영원히 멈춘다.
        leadership().lead();

        Long ttlSeconds = redis.getExpire(RedisRelayLeadership.KEY_PREFIX + SERVICE);

        assertThat(ttlSeconds).isNotNull().isPositive().isLessThanOrEqualTo(TTL.toSeconds());
    }

    private RelayLeadership leadership() {
        return new RedisRelayLeadership(redis, SERVICE, TTL);
    }

    private static LettuceConnectionFactory connectionFactory() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration(redisHost(), redisPort()));
        factory.afterPropertiesSet();
        return factory;
    }

    private static StringRedisTemplate template(LettuceConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }
}
