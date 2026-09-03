package com.dawnline.order.adapter.out.redis;

import com.dawnline.order.application.port.out.IdempotencyCache;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link IdempotencyCache} 의 Redis 구현 (DESIGN.md §5.1 · §7.2 {@code idem:order:{key}}).
 *
 * <h2>모든 메서드가 예외를 삼킨다</h2>
 * 불변규칙 7: Redis 는 진실 저장소가 아니다. Redis 장애가 주문 접수를 막으면 그 규칙을 어기는 것이고,
 * §8.4 가 요구하는 "의존 구성요소가 죽어도 쓰기 경로는 산다" 도 깨진다. 그래서 실패는
 * {@link Lock#UNAVAILABLE} 이나 조용한 무시로 바뀐다 — 정확성은 {@code idempotency_keys} 가 지킨다.
 *
 * <p>삼키는 예외는 {@link DataAccessException} 뿐이다. Spring Data Redis 가 연결 실패·타임아웃·
 * 프로토콜 오류를 전부 그 아래로 옮겨 준다. 그 밖의 예외(예: 프로그래밍 오류)는 그대로 올라간다.
 */
public class RedisIdempotencyCache implements IdempotencyCache {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyCache.class);

    /** §7.2 의 키 형식. */
    private static final String KEY_PREFIX = "idem:order:";

    /** §5.1 1단계의 {@code IN_PROGRESS} 값. */
    private static final String IN_PROGRESS = "IN_PROGRESS";

    /** §5.1 4단계의 {@code DONE} 값. */
    private static final String DONE = "DONE";

    /**
     * §5.1 의 {@code PX 30000}. 이 만료가 ADR-018 의 핵심이다 — 처리 도중 프로세스가 죽어도
     * 30초 뒤 잠금이 스스로 풀린다. 사람이 치우러 갈 것이 없다.
     */
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    /** §7.2 의 TTL 24h. 완료 표시는 그동안 DB 를 읽지 않고도 중복을 알아보게 해 준다. */
    private static final Duration DONE_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;

    /**
     * @param redis 문자열 전용 템플릿
     */
    public RedisIdempotencyCache(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    @Override
    public Lock tryLock(String key) {
        Objects.requireNonNull(key, "key");
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(redisKey(key), IN_PROGRESS, LOCK_TTL);
            // setIfAbsent 는 파이프라인·트랜잭션 안에서 null 을 돌려준다. 여기서는 그럴 일이 없지만,
            // null 을 "획득함" 으로 읽으면 잠금이 없는 채로 진행하게 되므로 보수적으로 다룬다.
            return Boolean.TRUE.equals(acquired) ? Lock.ACQUIRED : Lock.HELD;
        } catch (DataAccessException e) {
            log.warn("멱등 잠금을 얻지 못했습니다(Redis 불가). 잠금 없이 진행합니다 — 정확성은 DB 가 지킵니다.", e);
            return Lock.UNAVAILABLE;
        }
    }

    @Override
    public void markDone(String key) {
        Objects.requireNonNull(key, "key");
        try {
            redis.opsForValue().set(redisKey(key), DONE, DONE_TTL);
        } catch (DataAccessException e) {
            // 다음 요청은 DB 에서 같은 답을 얻는다. 여기서 던지면 이미 커밋된 주문이 실패로 보인다.
            log.warn("멱등 키의 완료 표시에 실패했습니다. 재요청은 DB 경로로 처리됩니다.", e);
        }
    }

    @Override
    public void release(String key) {
        Objects.requireNonNull(key, "key");
        try {
            redis.delete(redisKey(key));
        } catch (DataAccessException e) {
            // 못 지워도 30초 뒤 만료된다. 그 사이 재시도는 409 를 받는다.
            log.warn("멱등 잠금 해제에 실패했습니다. {} 뒤 만료됩니다.", LOCK_TTL, e);
        }
    }

    private static String redisKey(String key) {
        return KEY_PREFIX + key;
    }
}
