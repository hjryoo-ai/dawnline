package com.dawnline.fulfillment.adapter.out.redis;

import com.dawnline.common.Ids;
import com.dawnline.fulfillment.application.port.out.WaveLock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@link WaveLock} 의 Redis 구현 — {@code SET lock:wave:{id} <token> NX PX 60000} (§7.2).
 *
 * <h2>토큰을 두는 이유</h2>
 * 해제할 때 <strong>내가 잡은 락인지</strong> 확인해야 한다. 이 인스턴스가 멈춰 있는 동안 TTL 이
 * 만료되고 다른 인스턴스가 같은 키를 잡았다면, 뒤늦게 깨어난 이쪽의 {@code DEL} 이 남의 락을
 * 푼다. 비교와 삭제는 Lua 로 원자적으로 한다.
 *
 * <h2>Redis 장애는 fail-open 이다</h2>
 * 락을 <em>얻지 못한 것</em>과 Redis 가 <em>죽은 것</em>은 다르다. 전자는 다른 인스턴스가 처리
 * 중이라는 뜻이라 스킵하지만, 후자에 스킵하면 <strong>Redis 장애가 곧 마감 중단</strong>이 된다 —
 * 그리고 마감이 멈추면 웨이브가 영원히 열려 있고 계획이 시작되지 않는다. 중복 마감을 실제로
 * 막는 것은 {@code FOR UPDATE} 와 상태 전이이므로(불변규칙 7), 여기서는 진행하고 세어 둔다.
 * 레이트 리밋의 {@code bypassed} 와 같은 판단이다(§7.2).
 */
public class RedisWaveLock implements WaveLock {

    private static final Logger log = LoggerFactory.getLogger(RedisWaveLock.class);

    /** §7.2 의 키 접두어. */
    public static final String KEY_PREFIX = "lock:wave:";

    private static final RedisScript<Long> RELEASE = loadScript();

    private final StringRedisTemplate redis;
    private final GeoMetrics metrics;
    private final Duration ttl;

    /**
     * @param redis   문자열 전용 템플릿
     * @param metrics 폴백(fail-open)을 세는 메트릭
     * @param ttl     락 TTL (§7.2 기본 60초). 마감 트랜잭션보다 넉넉해야 하고, 프로세스가 죽어도
     *                이 시간 뒤에는 스스로 풀린다
     */
    public RedisWaveLock(StringRedisTemplate redis, GeoMetrics metrics, Duration ttl) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl 은 양수여야 합니다: " + ttl);
        }
    }

    @Override
    public Optional<Guard> tryLock(UUID waveId) {
        Objects.requireNonNull(waveId, "waveId");
        String key = KEY_PREFIX + waveId;
        String token = Ids.newId().toString();
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
            if (Boolean.TRUE.equals(acquired)) {
                metrics.servedByRedis("wave_lock");
                return Optional.of(() -> release(key, token));
            }
            // 다른 인스턴스가 처리 중이다. 스킵이 맞다 — 낭비를 줄이는 것이 이 락의 목적이다.
            return Optional.empty();
        } catch (RuntimeException e) {
            // Redis 가 죽었다. 스킵하면 마감이 멈추고, 그것은 폴백이 아니다.
            log.warn("웨이브 락을 쓸 수 없어 락 없이 진행합니다. waveId={}", waveId, e);
            metrics.servedByFallback("wave_lock");
            return Optional.of(() -> { });
        }
    }

    private void release(String key, String token) {
        try {
            redis.execute(RELEASE, List.of(key), token);
        } catch (RuntimeException e) {
            // TTL 이 결국 정리한다. 여기서 예외를 내면 마감 자체가 실패한 것처럼 보인다.
            log.debug("웨이브 락 해제 실패. TTL 이 정리합니다. key={}", key, e);
        }
    }

    private static RedisScript<Long> loadScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(read("redis/wave-lock-release.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private static String read(String path) {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Lua 스크립트를 읽을 수 없습니다: " + path, e);
        }
    }
}
