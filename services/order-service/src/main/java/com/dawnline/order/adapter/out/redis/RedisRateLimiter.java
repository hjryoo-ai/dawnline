package com.dawnline.order.adapter.out.redis;

import com.dawnline.order.OrderMetrics;
import com.dawnline.order.application.port.out.RateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * {@link RateLimiter} 의 Redis 구현 — Lua 토큰 버킷 (DESIGN.md §7.2, §8.3).
 *
 * <p>스크립트 본문은 {@code resources/redis/rate-limit-token-bucket.lua} 에 있다. 자바 문자열로
 * 박아 두면 문법 강조도 없고 diff 도 읽기 어렵다.
 *
 * <h2>실패는 통과시킨다 — 그러나 세어서 통과시킨다</h2>
 * Redis 를 못 쓰면 {@code BYPASSED} 다(§7.2 fail-open). 인증이 없는 API 에서 레이트 리밋은
 * <strong>유일한 남용 방지 수단</strong>이므로(§10), 그것이 꺼진 상태를 반드시 보이게 한다 —
 * {@code dawnline_rate_limit_decisions_total{outcome="bypassed"}} 가 그것이고 §9.4 가 알림을 건다.
 *
 * <p>{@link RedisOutageGate} 가 앞에 선다. 한 번 실패하면 그 뒤로는 Redis 를 <em>부르지도 않고</em>
 * 곧바로 통과시킨다. 타임아웃만 있으면 500 rps 에서 초당 500번씩 기다리게 되고, 그때 폴백은
 * 정확성을 지키면서 SLO 를 무너뜨린다(§8.1).
 */
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    /** §7.2 의 키 형식. */
    private static final String KEY_PREFIX = "rl:customer:";

    private static final String SCRIPT_PATH = "redis/rate-limit-token-bucket.lua";

    private final StringRedisTemplate redis;
    private final RedisOutageGate gate;
    private final Clock clock;
    private final MeterRegistry meters;
    private final RedisScript<List<Long>> script;
    private final int capacity;
    private final int refillPerSecond;
    private final int ttlSeconds;

    /**
     * @param redis           문자열 전용 템플릿
     * @param gate            Redis 장애 차단기
     * @param clock           버킷 리필의 기준 시각 (불변규칙 12)
     * @param meters          Micrometer 레지스트리
     * @param capacity        버킷 용량 (§7.2 기본 60)
     * @param refillPerSecond 초당 리필 개수 (§7.2 기본 1)
     * @param ttlSeconds      버킷 TTL (§7.2 기본 60)
     */
    public RedisRateLimiter(StringRedisTemplate redis, RedisOutageGate gate, Clock clock, MeterRegistry meters,
            int capacity, int refillPerSecond, int ttlSeconds) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.meters = Objects.requireNonNull(meters, "meters");
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity 는 1 이상이어야 합니다: " + capacity);
        }
        if (refillPerSecond < 1) {
            throw new IllegalArgumentException("refillPerSecond 는 1 이상이어야 합니다: " + refillPerSecond);
        }
        if (ttlSeconds < 1) {
            throw new IllegalArgumentException("ttlSeconds 는 1 이상이어야 합니다: " + ttlSeconds);
        }
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.ttlSeconds = ttlSeconds;

        this.script = loadScript();
    }

    /**
     * {@code DefaultRedisScript#setResultType} 은 {@code Class} 를 받으므로 {@code List<Long>} 을
     * 그대로 표현할 수 없다. 경계를 이 메서드 하나로 좁혀 두고, 밖에서는 제네릭 타입을 쓴다.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RedisScript<List<Long>> loadScript() {
        DefaultRedisScript loaded = new DefaultRedisScript<>();
        loaded.setScriptSource(new ResourceScriptSource(new ClassPathResource(SCRIPT_PATH)));
        loaded.setResultType(List.class);
        return (RedisScript<List<Long>>) loaded;
    }

    @Override
    public Decision tryAcquire(UUID customerId) {
        Objects.requireNonNull(customerId, "customerId");
        if (gate.isBypassing()) {
            return record(new Decision(Outcome.BYPASSED, 0));
        }
        try {
            List<Long> result = redis.execute(script, List.of(KEY_PREFIX + customerId),
                    Integer.toString(capacity),
                    Integer.toString(refillPerSecond),
                    Long.toString(clock.millis()),
                    Integer.toString(ttlSeconds));
            return record(decisionOf(result));
        } catch (DataAccessException e) {
            gate.recordFailure();
            log.warn("레이트 리밋을 판정하지 못했습니다(Redis 불가). 통과시킵니다 — "
                    + "무인증 API 의 남용 방지가 그동안 꺼져 있습니다 (§7.2, §9.4).", e);
            return record(new Decision(Outcome.BYPASSED, 0));
        }
    }

    /**
     * 스크립트 결과 해석. 모양이 예상과 다르면 <strong>통과시킨다</strong> —
     * 판정기의 버그로 주문 접수를 막는 것보다 남용 방지가 잠깐 꺼지는 쪽이 낫고,
     * 그 사실은 {@code bypassed} 로 보인다.
     */
    private Decision decisionOf(List<Long> result) {
        if (result == null || result.size() < 2 || result.get(0) == null) {
            log.warn("레이트 리밋 스크립트가 예상과 다른 값을 돌려줬습니다: {}", result);
            return new Decision(Outcome.BYPASSED, 0);
        }
        if (result.get(0) == 1L) {
            return new Decision(Outcome.ALLOWED, 0);
        }
        long retryAfter = result.get(1) == null ? 1L : result.get(1);
        return new Decision(Outcome.LIMITED, (int) Math.max(1L, retryAfter));
    }

    private Decision record(Decision decision) {
        Counter.builder(OrderMetrics.RATE_LIMIT_DECISIONS)
                .description("고객별 레이트 리밋 판정 (§7.2)")
                .tag(OrderMetrics.TAG_OUTCOME, decision.outcome().name().toLowerCase(java.util.Locale.ROOT))
                .register(meters)
                .increment();
        return decision;
    }
}
